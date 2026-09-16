package io.micronaut.servlet.websocket;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Header;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.websocket.WebSocketSession;
import jakarta.inject.Singleton;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Decoder;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicronautWebSocketContainerTest {

    static final String TEST = "MicronautWebSocketContainerTest";
    static final URI ECHO = URI.create("ws://localhost:1234/echo");

    static ApplicationContext context;
    static WebSocketContainer container;
    static WebSocketContainerHolder holder;

    TestContainer delegate;

    @BeforeAll
    static void start() {
        context = ApplicationContext.run(Map.of("test.name", TEST));
        container = context.getBean(WebSocketContainer.class);
        holder = context.getBean(WebSocketContainerHolder.class);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @BeforeEach
    void freshDelegate() {
        delegate = new TestContainer();
        delegate.session.autoCompleteSends = true;
        holder.register(delegate);
    }

    @Test
    void theInjectedContainerIsTheMicronautOne() {
        assertInstanceOf(MicronautWebSocketContainer.class, container);
    }

    @Test
    void aClientEndpointBeanIsOpenedByAMicronautEndpointNotScannedByTheContainer() throws Exception {
        int instancesBefore = EchoClient.INSTANCES.get();

        Session session = container.connectToServer(EchoClient.class, ECHO);

        assertSame(delegate.session, session);
        assertInstanceOf(MicronautClientEndpoint.class, delegate.endpoint, "the container is handed a programmatic endpoint");
        assertNull(delegate.scannedClass, "the container must never scan the user's class");
        assertEquals(ECHO, delegate.uri);
        assertEquals(instancesBefore + 1, EchoClient.INSTANCES.get(), "one endpoint instance per connection");
        assertEquals(List.of("chat", "superchat"), delegate.config.getPreferredSubprotocols());
        assertTrue(delegate.config.getDecoders().isEmpty(), "decoders run on the Micronaut side, the container never sees them");
    }

    @Test
    void anInstanceIsUsedAsGivenAndItsHandlersRunOnCompiledMetadata() throws Exception {
        EchoClient client = new EchoClient();

        Session session = container.connectToServer(client, ECHO);

        assertSame(session, client.session, "@OnOpen received the container's session before connectToServer returned");
        assertNotNull(client.config);
        assertEquals("test", client.containerHeader, "@Header binds against the handshake headers the container sent");
        assertEquals("hi", client.greetingHeader, "the declared configurator saw the handshake first");
        assertTrue(GreetingConfigurator.beforeRequest);

        delegate.session.receive(String.class, "shout hello");
        assertEquals(List.of(new Shout("hello")), client.received, "the declared decoder decoded the message");
        assertEquals("reply:HELLO", delegate.session.sentText.peekLast(), "the return value went through the declared encoder");

        delegate.session.receive(ByteBuffer.class, ByteBuffer.wrap(new byte[] {1, 2}));
        assertEquals(2, client.received.size());
        assertEquals("[1, 2]", client.received.get(1).toString());

        delegate.endpoint.onError(session, new IllegalStateException("boom"));
        assertInstanceOf(IllegalStateException.class, client.error);
        assertTrue(delegate.session.open, "a handler failure does not close the connection");

        delegate.endpoint.onClose(session, new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "bye"));
        assertEquals(CloseReason.CloseCodes.GOING_AWAY, client.closeReason.getCloseCode());
        assertTrue(Shout.decoderDestroyed, "the codecs are destroyed with the session");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMessageArrivingWhileOnOpenIsStillRunningIsDeliveredOnlyOnceItHasCompleted() throws Exception {
        SlowOpenClient client = new SlowOpenClient();
        BeanDefinition<Object> definition = (BeanDefinition<Object>) (BeanDefinition<?>) context.getBeanDefinition(SlowOpenClient.class);
        MicronautClientEndpoint endpoint = new MicronautClientEndpoint(
            context.getBean(ServletWebSocketSupport.class),
            ClientEndpointBean.of(definition, client),
            JakartaEndpoint.of(definition),
            HttpRequest.GET(ECHO),
            PropagatedContext.empty()
        );

        endpoint.onOpen(delegate.session, ClientEndpointConfig.Builder.create().build());
        assertTrue(client.opening.await(5, TimeUnit.SECONDS), "@OnOpen was invoked");
        assertFalse(endpoint.opened().isDone(), "an asynchronous @OnOpen has not completed yet");

        delegate.session.receive(String.class, "early");
        assertTrue(client.received.isEmpty(), "a message must not reach the endpoint before @OnOpen has completed");

        client.release.complete(null);
        endpoint.opened().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("early"), client.received, "the held message is delivered once @OnOpen has completed");

        delegate.session.receive(String.class, "late");
        assertEquals(List.of("early", "late"), client.received);
    }

    @Test
    @SuppressWarnings("unchecked")
    void messagesHeldForAnOpenHandlerDieWithTheSession() {
        SlowOpenClient client = new SlowOpenClient();
        BeanDefinition<Object> definition = (BeanDefinition<Object>) (BeanDefinition<?>) context.getBeanDefinition(SlowOpenClient.class);
        MicronautClientEndpoint endpoint = new MicronautClientEndpoint(
            context.getBean(ServletWebSocketSupport.class),
            ClientEndpointBean.of(definition, client),
            JakartaEndpoint.of(definition),
            HttpRequest.GET(ECHO),
            PropagatedContext.empty()
        );
        endpoint.onOpen(delegate.session, ClientEndpointConfig.Builder.create().build());
        delegate.session.receive(String.class, "early");

        endpoint.onClose(delegate.session, new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "bye"));
        client.release.complete(null);

        assertTrue(endpoint.opened().isCompletedExceptionally(), "the open failed: the session closed first");
        assertTrue(client.received.isEmpty(), "a message held for @OnOpen is not delivered after @OnClose");
    }

    @Test
    void aProgrammaticEndpointIsInstantiatedWithoutReflectionWhenItCanBeAndByTheContainerOtherwise() throws Exception {
        ClientEndpointConfig config = ClientEndpointConfig.Builder.create().build();

        container.connectToServer(IntrospectedEndpoint.class, config, ECHO);
        assertInstanceOf(IntrospectedEndpoint.class, delegate.endpoint, "an introspected endpoint is created through its introspection");
        assertNull(delegate.programmaticClass);

        container.connectToServer(PlainEndpoint.class, config, ECHO);
        assertEquals(PlainEndpoint.class, delegate.programmaticClass, "one nothing can build without reflection is left to the container");
    }

    @Test
    void anOpenHandlerThatNeverCompletesFailsTheConnectionAfterTheConnectTimeout() {
        try (ApplicationContext impatient = ApplicationContext.run(Map.of(
            "test.name", TEST, "micronaut.servlet.websocket.connect-timeout", "200ms"))) {
            impatient.getBean(WebSocketContainerHolder.class).register(delegate);
            WebSocketContainer impatientContainer = impatient.getBean(WebSocketContainer.class);

            DeploymentException e = assertThrows(DeploymentException.class, () -> impatientContainer.connectToServer(new SlowOpenClient(), ECHO));

            assertTrue(e.getMessage().contains("connect-timeout"), e.getMessage());
            assertFalse(delegate.session.open, "the session is closed when the open handler does not complete");
        }
    }

    @Test
    void theDeclaredConfiguratorIsCreatedPerConnection() throws Exception {
        int before = GreetingConfigurator.INSTANCES.get();

        container.connectToServer(new EchoClient(), ECHO);
        container.connectToServer(new EchoClient(), ECHO);

        assertEquals(before + 2, GreetingConfigurator.INSTANCES.get(), "no handshake state is shared between connections");
    }

    @Test
    void aClientEndpointWithNoHandlerIsLeftToTheContainer() throws Exception {
        container.connectToServer(HandlerlessClient.class, ECHO);

        assertEquals(HandlerlessClient.class, delegate.scannedClass, "nothing was mapped, so nothing is lost by letting the container scan it");
    }

    @Test
    void aFailingOpenHandlerFailsTheConnectionAndClosesTheSession() {
        DeploymentException e = assertThrows(DeploymentException.class, () -> container.connectToServer(FailingClient.class, ECHO));

        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals("no", e.getCause().getMessage());
        assertFalse(delegate.session.open, "a session whose @OnOpen failed must not stay connected");
        assertInstanceOf(IllegalStateException.class, FailingClient.lastError,
            "@OnError was offered the failure, and had completed on its own executor, before the failure was reported");
    }

    @Test
    void aClassThatIsNotAClientEndpointBeanIsHandedToTheContainerAsItIs() throws Exception {
        NotABean instance = new NotABean();

        container.connectToServer(NotABean.class, ECHO);
        assertEquals(NotABean.class, delegate.scannedClass);

        container.connectToServer(instance, ECHO);
        assertSame(instance, delegate.scannedInstance);
    }

    @Test
    void theContainerSettingsAreTheDelegates() {
        container.setDefaultMaxTextMessageBufferSize(4096);
        container.setDefaultMaxSessionIdleTimeout(1000);

        assertEquals(4096, delegate.maxTextMessageBufferSize);
        assertEquals(1000, delegate.maxSessionIdleTimeout);
        assertEquals(4096, container.getDefaultMaxTextMessageBufferSize());
        assertTrue(container.getInstalledExtensions().isEmpty());
    }

    record Shout(String text) {
        static boolean decoderDestroyed;
    }

    /**
     * Plain, so it is instantiated through the introspection the processor generates.
     */
    public static class ShoutDecoder implements Decoder.Text<Shout> {
        @Override
        public Shout decode(String s) {
            return new Shout(s.substring("shout ".length()));
        }

        @Override
        public boolean willDecode(String s) {
            return s.startsWith("shout ");
        }

        @Override
        public void destroy() {
            Shout.decoderDestroyed = true;
        }
    }

    @Singleton
    @Requires(property = "test.name", value = TEST)
    static class ShoutEncoder implements Encoder.Text<Shout> {
        @Override
        public String encode(Shout shout) {
            return "reply:" + shout.text().toUpperCase();
        }
    }

    public static class GreetingConfigurator extends ClientEndpointConfig.Configurator {
        static final AtomicInteger INSTANCES = new AtomicInteger();
        static boolean beforeRequest;

        public GreetingConfigurator() {
            INSTANCES.incrementAndGet();
        }

        @Override
        public void beforeRequest(Map<String, List<String>> headers) {
            beforeRequest = true;
            headers.put("X-Greeting", List.of("hi"));
        }
    }

    @Requires(property = "test.name", value = TEST)
    @ClientEndpoint(subprotocols = {"chat", "superchat"}, decoders = ShoutDecoder.class, encoders = ShoutEncoder.class, configurator = GreetingConfigurator.class)
    static class EchoClient {
        static final AtomicInteger INSTANCES = new AtomicInteger();

        final List<Object> received = new ArrayList<>();
        Session session;
        EndpointConfig config;
        String containerHeader;
        String greetingHeader;
        CloseReason closeReason;
        Throwable error;

        EchoClient() {
            INSTANCES.incrementAndGet();
        }

        @OnOpen
        public void open(Session session,
                         EndpointConfig config,
                         WebSocketSession micronautSession,
                         @Header("X-Container") String containerHeader,
                         @Header("X-Greeting") String greetingHeader) {
            this.session = session;
            this.config = config;
            this.containerHeader = containerHeader;
            this.greetingHeader = greetingHeader;
        }

        @OnMessage
        public Shout text(Shout shout) {
            received.add(shout);
            return shout;
        }

        @OnMessage
        public void binary(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            received.add(java.util.Arrays.toString(bytes));
        }

        @OnClose
        public void close(CloseReason reason) {
            this.closeReason = reason;
        }

        @OnError
        public void error(Throwable error) {
            this.error = error;
        }
    }

    @Requires(property = "test.name", value = TEST)
    @ClientEndpoint
    static class FailingClient {
        static Throwable lastError;

        @OnOpen
        public void open() {
            throw new IllegalStateException("no");
        }

        /**
         * Completes on another thread, so the failure may only be reported once this has.
         */
        @OnError
        public CompletableFuture<Void> error(Throwable error) {
            return CompletableFuture.runAsync(() -> lastError = error, CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS));
        }
    }

    /**
     * An {@code @OnOpen} that completes asynchronously, as a reactive or suspending one does.
     */
    @Requires(property = "test.name", value = TEST)
    @ClientEndpoint
    static class SlowOpenClient {
        final CountDownLatch opening = new CountDownLatch(1);
        final CompletableFuture<Void> release = new CompletableFuture<>();
        final List<String> received = new ArrayList<>();

        @OnOpen
        public CompletableFuture<Void> open() {
            opening.countDown();
            return release;
        }

        @OnMessage
        public void text(String message) {
            received.add(message);
        }
    }

    @Requires(property = "test.name", value = TEST)
    @ClientEndpoint
    static class HandlerlessClient {
    }

    static class NotABean {
    }

    @io.micronaut.core.annotation.Introspected
    public static class IntrospectedEndpoint extends jakarta.websocket.Endpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            // only its instantiation matters
        }
    }

    public static class PlainEndpoint extends jakarta.websocket.Endpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            // only its instantiation matters
        }
    }
}
