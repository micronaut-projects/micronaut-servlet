/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.websocket;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.bind.BoundExecutable;
import io.micronaut.core.bind.DefaultExecutableBinder;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.execution.CompletableFutureExecutionFlow;
import io.micronaut.core.execution.DelayedExecutionFlow;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.execution.ImmediateExecutor;
import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.KotlinUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.bind.binders.ContinuationArgumentBinder;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.reactive.execution.ReactiveExecutionFlow;
import io.micronaut.http.server.CoroutineHelper;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.MethodExecutionHandle;
import io.micronaut.web.router.websocket.WebSocketRouteEndpoint;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketPongMessage;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.bind.WebSocketState;
import io.micronaut.websocket.context.WebSocketBean;
import io.micronaut.websocket.event.WebSocketMessageProcessedEvent;
import io.micronaut.websocket.exceptions.WebSocketSessionException;
import jakarta.websocket.DecodeException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.context.Context;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The dispatch a Jakarta {@link Endpoint} performs onto a Micronaut WebSocket bean, shared by the
 * server and the client side.
 *
 * <p>The two programming models differ in what a handler receives and returns. A Jakarta endpoint
 * has one handler per message category, may take the container's {@link Session}, the
 * {@link EndpointConfig} and the Jakarta {@link jakarta.websocket.CloseReason}, decodes with the
 * decoders it declares, and the value its {@code @OnMessage} returns is sent back to the peer. Each
 * of those is bridged here; a Micronaut endpoint is handled as before.</p>
 *
 * <p>A subclass decides how the connection came about - accepted by the server or opened by the
 * client - and calls {@link #open} from its {@link #onOpen} with the pieces that differ: the bean,
 * the request the handlers are bound against and the Micronaut session. The hooks decide what
 * context handlers run under and what happens around the session's life.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
public abstract class AbstractMicronautEndpoint extends Endpoint {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractMicronautEndpoint.class);
    private static final String CONNECTION_RESET = "Connection reset";
    private static final String MAX_PAYLOAD_LENGTH = "maxPayloadLength";

    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object gate = new Object();
    private volatile boolean opened;
    /**
     * Messages that arrived before {@code @OnOpen} completed, guarded by {@link #gate}; {@code null}
     * once the open handler has completed and messages go straight through.
     */
    private @Nullable List<Runnable> deferred = new ArrayList<>();

    private @Nullable ServletWebSocketSupport support;
    private @Nullable WebSocketBean<Object> webSocketBean;
    private @Nullable HttpRequest<?> originatingRequest;
    private @Nullable ServletWebSocketSession micronautSession;
    private @Nullable Session nativeSession;
    private @Nullable EndpointConfig endpointConfig;
    private @Nullable JakartaEndpoint jakartaEndpoint;
    private @Nullable JakartaCodecs codecs;
    private @Nullable ExecutableMethod<Object, ?> textMethod;
    private @Nullable ExecutableMethod<Object, ?> binaryMethod;
    private @Nullable ExecutableMethod<Object, ?> pongMethod;
    private @Nullable Argument<?> textBodyArgument;
    private @Nullable Argument<?> binaryBodyArgument;
    private @Nullable Argument<?> pongBodyArgument;
    /**
     * The endpoint of a WebSocket route of the route builder, or {@code null} for a bean.
     */
    private @Nullable WebSocketRouteEndpoint routeEndpoint;
    /**
     * The executor of a WebSocket route of the route builder, which runs its handlers.
     */
    private Executor routeExecutor = ImmediateExecutor.INSTANCE;
    /**
     * The messages of a WebSocket route of the route builder, handled in their turn.
     */
    private @Nullable RouteMessageQueue routeMessages;

    /**
     * Default constructor.
     */
    protected AbstractMicronautEndpoint() {
    }

    /**
     * Sets the connection up and invokes {@code @OnOpen}.
     *
     * <p>The handlers are resolved and validated, the limits applied and the message handlers
     * registered with the container before the open handler runs, so a listener never sees a
     * close that had no matching open and the endpoint never receives a message it has no handler
     * for.</p>
     *
     * @param session            The container's session
     * @param config             The endpoint configuration the session was opened with
     * @param support            The shared infrastructure
     * @param webSocketBean      The bean the connection is dispatched to
     * @param jakartaEndpoint    What the bean declares as a Jakarta endpoint, or {@code null} for
     *                           a Micronaut one
     * @param originatingRequest The request handler arguments are bound against
     * @param micronautSession   The Micronaut view of the session
     * @return {@code true} when the connection is set up, {@code false} when it could not be
     * and the session was closed
     */
    protected final boolean open(Session session,
                                 EndpointConfig config,
                                 ServletWebSocketSupport support,
                                 WebSocketBean<Object> webSocketBean,
                                 @Nullable JakartaEndpoint jakartaEndpoint,
                                 HttpRequest<?> originatingRequest,
                                 ServletWebSocketSession micronautSession) {
        this.support = support;
        this.webSocketBean = webSocketBean;
        this.jakartaEndpoint = jakartaEndpoint;
        this.originatingRequest = originatingRequest;
        this.nativeSession = session;
        this.endpointConfig = config;
        this.micronautSession = micronautSession;
        if (webSocketBean instanceof WebSocketRouteEndpoint endpoint) {
            routeOpened(endpoint);
        }

        if (!resolveHandlers(session, config)) {
            return false;
        }
        applyLimits(session, declaredMaxPayloadLength(textMethod), declaredMaxPayloadLength(binaryMethod));
        if (!resolveBodyArguments(session)) {
            return false;
        }

        sessionOpened(micronautSession);
        registerMessageHandlers(session);
        if (routeEndpoint != null) {
            connectRoute(routeEndpoint, micronautSession);
        }

        ExecutableMethod<Object, ?> openMethod = handler(webSocketBean.openMethod());
        opened = true;
        if (openMethod != null) {
            invokeOpen(openMethod);
        } else {
            completeOpen();
        }
        return true;
    }

    /**
     * Lets messages through and delivers the ones that arrived while {@code @OnOpen} was running.
     *
     * <p>The specification has the open handler complete before the first message is delivered.
     * That holds by itself when handlers run on the calling thread, but an {@code @ExecuteOn}
     * handler completes later, so the message handlers are registered with the container at
     * once - a container may refuse a message it has no handler for - and what arrives in the
     * meantime is held back here.</p>
     */
    private void completeOpen() {
        RouteMessageQueue messages = routeMessages;
        if (messages != null) {
            messages.opened();
            openCompleted();
            return;
        }
        synchronized (gate) {
            // Replayed under the gate: a message the container delivers meanwhile waits its turn
            // behind the backlog rather than overtaking it.
            List<Runnable> held = deferred;
            deferred = null;
            if (held != null && !closed.get()) {
                held.forEach(Runnable::run);
            }
        }
        openCompleted();
    }

    /**
     * Sets a connection to a WebSocket route of the route builder up: its handlers run on the
     * executor of the route, and its messages are handled in their turn.
     *
     * @param endpoint The endpoint of the route
     */
    private void routeOpened(WebSocketRouteEndpoint endpoint) {
        this.routeEndpoint = endpoint;
        Executor executor = routeExecutor();
        if (executor != null) {
            this.routeExecutor = executor;
        }
        this.routeMessages = new RouteMessageQueue(endpoint.maxConcurrentMessages(), endpoint.maxPendingMessages());
        synchronized (gate) {
            // the queue of the route holds the messages back until the open handler is done
            deferred = null;
        }
    }

    /**
     * Connects the session to the endpoint of a WebSocket route, whose stream of messages, if any,
     * signals its subscriber on the executor of the route with the upgrade request as the current
     * request.
     */
    private void connectRoute(WebSocketRouteEndpoint endpoint, ServletWebSocketSession session) {
        Executor executor = routeExecutor;
        PropagatedContext context = propagatedContext();
        endpoint.connected(session, command -> executor.execute(context.wrap(command)), this::forwardError);
    }

    /**
     * The executor of a WebSocket route of the route builder, which runs its handlers.
     *
     * @return The executor, or {@code null} to run them on the thread that delivers what they handle
     */
    protected @Nullable Executor routeExecutor() {
        return null;
    }

    private void dispatch(Runnable message) {
        synchronized (gate) {
            if (deferred != null) {
                deferred.add(message);
                return;
            }
        }
        message.run();
    }

    /**
     * The context handlers run under, on top of which the request is propagated for a
     * {@code suspend} handler.
     *
     * @return The propagated context
     */
    protected abstract PropagatedContext propagatedContext();

    /**
     * Called once the connection is set up, before the message handlers are registered and the
     * open handler runs. Nothing by default.
     *
     * @param session The Micronaut session
     */
    protected void sessionOpened(ServletWebSocketSession session) {
        // nothing by default
    }

    /**
     * Called once {@code @OnOpen} has completed, or straight away when there is none. Nothing by
     * default.
     */
    protected void openCompleted() {
        // nothing by default
    }

    /**
     * Called when {@code @OnOpen} failed, after {@code @OnError} was offered the failure and
     * before the session is closed. Nothing by default.
     *
     * @param cause The failure
     */
    protected void openFailed(Throwable cause) {
        // nothing by default
    }

    /**
     * Called once the session is closed and its close handler has run.
     *
     * @param session The Micronaut session
     * @param opened  Whether the session had reached the open event
     */
    protected void sessionClosed(ServletWebSocketSession session, boolean opened) {
        // nothing by default
    }

    /**
     * Applies the payload limits a handler declares as {@code maxMessageSize}; a limit no handler
     * declares is left to the container.
     *
     * @param session          The container's session
     * @param declaredTextMax  The text limit the text handler declares, or {@code null}
     * @param declaredBinaryMax The binary limit the binary handler declares, or {@code null}
     */
    protected void applyLimits(Session session, @Nullable Integer declaredTextMax, @Nullable Integer declaredBinaryMax) {
        if (declaredTextMax != null) {
            session.setMaxTextMessageBufferSize(declaredTextMax);
        }
        if (declaredBinaryMax != null) {
            session.setMaxBinaryMessageBufferSize(declaredBinaryMax);
        }
    }

    /**
     * The shared infrastructure.
     *
     * @return The support, once {@link #open} was called
     */
    protected final ServletWebSocketSupport support() {
        return support;
    }

    /**
     * The bean the connection is dispatched to.
     *
     * @return The bean, once {@link #open} was called
     */
    protected final WebSocketBean<Object> webSocketBean() {
        return webSocketBean;
    }

    /**
     * The Micronaut view of the session.
     *
     * @return The session, once {@link #open} was called
     */
    protected final @Nullable ServletWebSocketSession micronautSession() {
        return micronautSession;
    }

    /**
     * Picks the message handlers: one per category for a Jakarta endpoint, together with its
     * codecs; the one handler of a Micronaut endpoint for text and binary alike.
     *
     * @return {@code false} if the endpoint could not be set up and the session was closed
     */
    private boolean resolveHandlers(Session session, EndpointConfig config) {
        if (jakartaEndpoint == null) {
            ExecutableMethod<Object, ?> messageMethod = handler(webSocketBean.messageMethod());
            this.textMethod = messageMethod;
            this.binaryMethod = messageMethod;
            this.pongMethod = handler(webSocketBean.pongMethod());
            return true;
        }
        try {
            this.codecs = JakartaCodecs.create(jakartaEndpoint, support.components(), config);
        } catch (Exception e) {
            LOG.error("Error creating the decoders and encoders of WebSocket endpoint [{}]: {}",
                webSocketBean.getBeanDefinition().getBeanType().getName(), e.getMessage(), e);
            closeQuietly(session, CloseReason.INTERNAL_ERROR);
            return false;
        }
        this.textMethod = jakartaEndpoint.textMethod();
        this.binaryMethod = jakartaEndpoint.binaryMethod();
        this.pongMethod = jakartaEndpoint.pongMethod();
        return true;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable ExecutableMethod<Object, ?> handler(Optional<? extends MethodExecutionHandle<?, ?>> handle) {
        return handle.map(h -> (ExecutableMethod<Object, ?>) h.getExecutableMethod()).orElse(null);
    }

    /**
     * Finds the message parameter of each handler.
     *
     * @return {@code false} if a handler has none and the session was closed
     */
    private boolean resolveBodyArguments(Session session) {
        if (routeEndpoint != null) {
            // the handler functions declare the type of their message: it is never bound from the
            // upgrade request, e.g. from a query parameter of the same name
            this.textBodyArgument = routeEndpoint.messageArgument();
            this.binaryBodyArgument = textBodyArgument;
            this.pongBodyArgument = routeEndpoint.pongArgument();
            return true;
        }
        if (textMethod != null) {
            this.textBodyArgument = resolveBodyArgument(textMethod, false);
            if (textBodyArgument == null) {
                failHandler(session, textMethod);
                return false;
            }
        }
        if (binaryMethod != null) {
            this.binaryBodyArgument = binaryMethod == textMethod
                ? textBodyArgument
                : resolveBodyArgument(binaryMethod, false);
            if (binaryBodyArgument == null) {
                failHandler(session, binaryMethod);
                return false;
            }
        }
        if (pongMethod != null) {
            this.pongBodyArgument = resolveBodyArgument(pongMethod, true);
            if (pongBodyArgument == null) {
                LOG.error("WebSocket pong handler [{}] must declare exactly one pong message argument", pongMethod);
            }
        }
        return true;
    }

    private void registerMessageHandlers(Session session) {
        // a WebSocket route without a message handler closes the connection on a message, like on
        // Netty, rather than leave it to the container, which may ignore it
        boolean route = routeEndpoint != null;
        if (textMethod != null || route) {
            session.addMessageHandler(String.class, (MessageHandler.Whole<String>) this::onTextMessage);
        }
        if (binaryMethod != null || route) {
            session.addMessageHandler(ByteBuffer.class, (MessageHandler.Whole<ByteBuffer>) this::onBinaryMessage);
        }
        if (pongBodyArgument != null) {
            session.addMessageHandler(PongMessage.class, (MessageHandler.Whole<PongMessage>) this::onPongMessage);
        }
    }

    private static void failHandler(Session session, ExecutableMethod<?, ?> handler) {
        LOG.error("WebSocket @OnMessage method [{}] must declare exactly one message body argument", handler);
        closeQuietly(session, CloseReason.INTERNAL_ERROR);
    }

    @Override
    public void onClose(Session session, jakarta.websocket.CloseReason closeReason) {
        handleClose(new CloseReason(
            closeReason.getCloseCode().getCode(),
            closeReason.getReasonPhrase()
        ), closeReason);
    }

    @Override
    public void onError(Session session, Throwable throwable) {
        forwardError(throwable);
    }

    /**
     * Reads {@code maxPayloadLength} only when the endpoint actually declared it.
     *
     * <p>The annotation carries a default, so a value read straight from the metadata cannot
     * be told apart from an unset one, and the configured size would then never apply.</p>
     *
     * @param messageMethod The message handler, or {@code null} when there is none
     * @return The declared payload length, or {@code null} when the endpoint did not declare one
     */
    private static @Nullable Integer declaredMaxPayloadLength(@Nullable ExecutableMethod<Object, ?> messageMethod) {
        if (messageMethod == null) {
            return null;
        }
        return messageMethod.getAnnotationMetadata()
            .findAnnotation(OnMessage.class)
            .filter(annotation -> annotation.contains(MAX_PAYLOAD_LENGTH))
            .flatMap(annotation -> annotation.intValue(MAX_PAYLOAD_LENGTH).stream().boxed().findFirst())
            .orElse(null);
    }

    private void onTextMessage(String message) {
        RouteMessageQueue messages = routeMessages;
        if (messages != null) {
            messages.offer(() -> handleMessage(textMethod, textBodyArgument, message, null));
            return;
        }
        dispatch(() -> handleMessage(textMethod, textBodyArgument, message, null));
    }

    private void onBinaryMessage(ByteBuffer message) {
        // Copied before anything is deferred: the container may reuse the buffer once this returns.
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        RouteMessageQueue messages = routeMessages;
        if (messages != null) {
            messages.offer(() -> handleMessage(binaryMethod, binaryBodyArgument, null, bytes));
            return;
        }
        dispatch(() -> handleMessage(binaryMethod, binaryBodyArgument, null, bytes));
    }

    private void onPongMessage(PongMessage message) {
        if (pongMethod == null || pongBodyArgument == null) {
            return;
        }
        Object pong = pongOf(message);
        if (routeEndpoint != null) {
            // like a ping, a pong of a WebSocket route is handled at once, not in the turn of the messages
            invoke(pongMethod, Map.of(pongBodyArgument, pong), null);
            return;
        }
        dispatch(() -> invoke(pongMethod, Map.of(pongBodyArgument, pong), null));
    }

    private Object pongOf(PongMessage message) {
        Object pong;
        if (pongBodyArgument.getType().isInstance(message)) {
            // A Jakarta handler takes the container's PongMessage as it is.
            pong = message;
        } else {
            ByteBuffer applicationData = message.getApplicationData();
            byte[] bytes = new byte[applicationData.remaining()];
            applicationData.get(bytes);
            pong = new WebSocketPongMessage(ByteArrayBufferFactory.INSTANCE.wrap(bytes));
        }
        return pong;
    }

    /**
     * Invokes {@code @OnOpen}, closing the connection if it fails.
     *
     * <p>A handler failure is offered to {@code @OnError} first, but unlike a message
     * failure the session is always closed afterwards: an endpoint whose initialization
     * failed must not go on receiving messages. This matches the Netty server, which closes
     * unconditionally when the open method or its binding fails.</p>
     *
     * @param openMethod The open handler
     */
    private void invokeOpen(ExecutableMethod<Object, ?> openMethod) {
        invokeHandler(openMethod, Map.of(), false).onComplete((ignored, error) -> {
            if (error != null) {
                failOpen(error);
            } else {
                completeOpen();
            }
        });
    }

    /**
     * Offers the failure to {@code @OnError}, then reports it and closes the session, in that
     * order, so a caller told of the failure can rely on the error handler having run.
     */
    private void failOpen(Throwable cause) {
        if (LOG.isErrorEnabled()) {
            LOG.error("Error opening WebSocket session: {}", cause.getMessage(), cause);
        }
        forwardError(cause).onComplete((ignored, error) -> {
            try {
                openFailed(cause);
            } finally {
                closeSession(CloseReason.INTERNAL_ERROR);
            }
        });
    }

    /**
     * Decodes a message and invokes its handler.
     *
     * @return A flow completing once the message is done with, its error handler included
     */
    private ExecutionFlow<?> handleMessage(@Nullable ExecutableMethod<Object, ?> messageMethod,
                                           @Nullable Argument<?> bodyArgument,
                                           @Nullable String text,
                                           byte @Nullable [] bytes) {
        if (messageMethod == null || bodyArgument == null) {
            closeSession(CloseReason.UNSUPPORTED_DATA);
            return ExecutionFlow.just(null);
        }
        Object decoded;
        try {
            decoded = decode(messageMethod, bodyArgument, text, bytes);
        } catch (Exception e) {
            return forwardError(e);
        }
        if (decoded == null) {
            closeSession(new CloseReason(
                CloseReason.UNSUPPORTED_DATA.getCode(),
                "Received data cannot be data to target type: " + bodyArgument
            ));
            return ExecutionFlow.just(null);
        }
        return invoke(messageMethod, Map.of(bodyArgument, decoded), decoded);
    }

    /**
     * Decodes a message for the handler's parameter: the declared Jakarta decoders first, then
     * the stream and buffer views of the payload, then Micronaut's conversion and message body
     * readers.
     */
    @SuppressWarnings("unchecked")
    private @Nullable Object decode(ExecutableMethod<Object, ?> messageMethod,
                                    Argument<?> bodyArgument,
                                    @Nullable String text,
                                    byte @Nullable [] bytes) throws DecodeException, IOException {
        if (codecs != null && !codecs.isEmpty()) {
            Object decoded = text != null
                ? codecs.decodeText(text, bodyArgument)
                : codecs.decodeBinary(bytes, bodyArgument);
            if (decoded != null) {
                return decoded;
            }
        }
        Object view = payloadView(bodyArgument.getType(), text, bytes);
        if (view != null) {
            return view;
        }
        Object raw = text != null ? text : bytes;
        Object converted = support.conversionService()
            .convert(raw, ConversionContext.of((Argument<Object>) bodyArgument))
            .orElse(null);
        if (converted != null) {
            return converted;
        }
        byte[] data = bytes != null ? bytes : text.getBytes(StandardCharsets.UTF_8);
        MediaType mediaType = messageMethod.stringValue(Consumes.class)
            .map(MediaType::of)
            .orElse(MediaType.APPLICATION_JSON_TYPE);
        MessageBodyReader<Object> reader = support.messageBodyHandlerRegistry()
            .findReader((Argument<Object>) bodyArgument, List.of(mediaType))
            .orElse(null);
        if (reader == null) {
            return null;
        }
        return reader.read(
            (Argument<Object>) bodyArgument,
            mediaType,
            new SimpleHttpHeaders(support.conversionService()),
            new ByteArrayInputStream(data)
        );
    }

    /**
     * The stream and buffer views of a payload that the specification lets a handler take.
     *
     * @return The view, or {@code null} when the parameter is not one of them
     */
    private static @Nullable Object payloadView(Class<?> type, @Nullable String text, byte @Nullable [] bytes) {
        if (type == Reader.class && text != null) {
            return new StringReader(text);
        }
        if (type == InputStream.class) {
            if (bytes != null) {
                return new ByteArrayInputStream(bytes);
            }
            return new ByteArrayInputStream(text != null ? text.getBytes(StandardCharsets.UTF_8) : new byte[0]);
        }
        if (type == ByteBuffer.class && bytes != null) {
            return ByteBuffer.wrap(bytes);
        }
        return null;
    }

    private @Nullable Argument<?> resolveBodyArgument(ExecutableMethod<Object, ?> method, boolean pong) {
        WebSocketState state = new WebSocketState(micronautSession, originatingRequest);
        BoundExecutable<Object, ?> probe = new DefaultExecutableBinder<WebSocketState>(frameworkArguments(method))
            .tryBind(method, support.binderRegistry(), state);
        List<Argument<?>> unbound = probe.getUnboundArguments();
        if (unbound.size() != 1) {
            return null;
        }
        Argument<?> argument = unbound.get(0);
        if (pong
            && !argument.getType().isAssignableFrom(WebSocketPongMessage.class)
            && !argument.getType().isAssignableFrom(PongMessage.class)) {
            return null;
        }
        return argument;
    }

    /**
     * The arguments every handler may take by type and that no binder knows: the container's
     * {@link Session} and the {@link EndpointConfig}, which is what a Jakarta handler declares.
     *
     * @param method The handler
     * @return The pre-bound arguments
     */
    private Map<Argument<?>, Object> frameworkArguments(ExecutableMethod<?, ?> method) {
        if (routeEndpoint != null) {
            // the handler functions of a route take the session and the upgrade request
            return preBind(method, micronautSession, originatingRequest);
        }
        return preBind(method, nativeSession, endpointConfig);
    }

    /**
     * Invokes a handler, offering its failure to the error handler.
     *
     * @return A flow completing once the handler is done, its error handler included
     */
    private ExecutionFlow<?> invoke(ExecutableMethod<Object, ?> method,
                                    Map<Argument<?>, Object> preBound,
                                    @Nullable Object processedMessage) {
        // Jakarta sends what an @OnMessage method returns; Micronaut does not.
        boolean sendResult = jakartaEndpoint != null && method.hasAnnotation(JakartaEndpoint.ON_MESSAGE);
        DelayedExecutionFlow<Object> done = DelayedExecutionFlow.create();
        invokeHandler(method, preBound, sendResult).onComplete((ignored, error) -> {
            if (error != null) {
                // the error handler first, then the next message
                forwardError(error).onComplete((v, e) -> done.complete(null));
                return;
            }
            try {
                if (processedMessage != null) {
                    support.applicationContext()
                        .publishEvent(new WebSocketMessageProcessedEvent<>(micronautSession, processedMessage));
                }
            } finally {
                done.complete(null);
            }
        });
        return done;
    }

    /**
     * Binds and invokes a handler on the executor selected for it, so that
     * {@code @ExecuteOn} is honoured, under the context the subclass propagates.
     *
     * <p>Binding happens inside the executor task rather than on the container thread,
     * because a Kotlin {@code suspend} handler has its continuation registered on the
     * request during binding and read back after the call. Doing both in one task, against a
     * request that belongs to this invocation, keeps concurrent messages on one session from
     * overwriting each other's continuation.</p>
     *
     * @param method     The handler
     * @param preBound   Arguments already resolved, such as the message body
     * @param sendResult Whether a value the handler returns is sent to the peer
     * @return A flow completing when the handler, and any publisher it returned, completes
     */
    private ExecutionFlow<Object> invokeHandler(ExecutableMethod<Object, ?> method,
                                                Map<Argument<?>, Object> preBound,
                                                boolean sendResult) {
        Executor executor = routeEndpoint != null
            ? routeExecutor(method)
            : support.executorSelector().selectExecutor(method, support.threadSelectionConfiguration());
        Map<Argument<?>, Object> bindings = bindings(method, preBound);
        PropagatedContext propagatedContext = propagatedContext();
        return ExecutionFlow.async(executor, () -> propagatedContext.propagate(() -> {
            try {
                return bindAndInvoke(method, bindings, sendResult, propagatedContext);
            } catch (Throwable e) { // NOSONAR an Error a handler throws must reach @OnError too, not the executor
                return ExecutionFlow.error(e);
            }
        }));
    }

    /**
     * The executor of a handler of a WebSocket route: the executor of the route, except for the
     * stream of the messages, which receives them in the order they were read and signals its
     * subscriber on the executor of the route.
     */
    private Executor routeExecutor(ExecutableMethod<Object, ?> method) {
        if (method == textMethod && routeEndpoint != null && routeEndpoint.streamsMessages()) {
            return ImmediateExecutor.INSTANCE;
        }
        return routeExecutor;
    }

    private Map<Argument<?>, Object> bindings(ExecutableMethod<Object, ?> method, Map<Argument<?>, Object> preBound) {
        Map<Argument<?>, Object> arguments = frameworkArguments(method);
        if (preBound.isEmpty()) {
            return arguments;
        }
        Map<Argument<?>, Object> combined = new LinkedHashMap<>(arguments);
        combined.putAll(preBound);
        return combined;
    }

    private ExecutionFlow<Object> bindAndInvoke(ExecutableMethod<Object, ?> method,
                                                Map<Argument<?>, Object> bindings,
                                                boolean sendResult,
                                                PropagatedContext propagatedContext) {
        boolean suspend = method.isSuspend();
        HttpRequest<?> request = suspend
            ? WebSocketHandshakeRequest.snapshot(originatingRequest)
            : originatingRequest;
        BoundExecutable<Object, ?> bound = new DefaultExecutableBinder<WebSocketState>(bindings)
            .bind(method, support.binderRegistry(), new WebSocketState(micronautSession, request));
        if (suspend) {
            ExecutionFlow<Object> completion = invokeSuspend(bound, request, propagatedContext);
            // A suspend handler's value is only known once the coroutine completes.
            return sendResult ? completion.flatMap(value -> sendResult(method, value, propagatedContext)) : completion;
        }
        Object result = bound.invoke(webSocketBean.getTarget());
        return sendResult ? sendResult(method, result, propagatedContext) : toFlow(result, propagatedContext);
    }

    /**
     * Invokes a Kotlin {@code suspend} handler.
     *
     * <p>The coroutine context has to be set up before the call, and a handler that really
     * suspends returns the {@code COROUTINE_SUSPENDED} marker rather than its result, so the
     * completion has to be picked up from the continuation instead. Without this a suspending
     * handler would be treated as already finished and neither its result nor its failure
     * would be seen.</p>
     *
     * @param bound             The bound handler
     * @param request           The request this invocation owns, which holds its continuation
     * @param propagatedContext The context to run the handler under
     * @return A flow completing when the coroutine completes
     */
    private ExecutionFlow<Object> invokeSuspend(BoundExecutable<Object, ?> bound,
                                                HttpRequest<?> request,
                                                PropagatedContext propagatedContext) {
        CoroutineHelper coroutineHelper = support.coroutineHelper();
        if (coroutineHelper == null) {
            return toFlow(bound.invoke(webSocketBean.getTarget()), propagatedContext);
        }
        coroutineHelper.setupCoroutineContext(request, Context.empty(), propagatedContext);
        Object result = bound.invoke(webSocketBean.getTarget());
        if (!KotlinUtils.isKotlinCoroutineSuspended(result)) {
            // The handler completed without suspending, so this is its actual return value.
            return toFlow(result, propagatedContext);
        }
        Supplier<CompletableFuture<?>> completion =
            ContinuationArgumentBinder.extractContinuationCompletableFutureSupplier(request);
        if (completion == null) {
            return ExecutionFlow.just(null);
        }
        return CompletableFutureExecutionFlow.just(completion.get().thenApply(Object.class::cast));
    }

    /**
     * Sends what a Jakarta {@code @OnMessage} handler returned, as the specification asks: a
     * plain value straight away, the value of a {@link CompletionStage} once it completes. A
     * publisher keeps the Micronaut meaning, completion of the handler, and is not sent.
     *
     * @param method            The handler
     * @param result            The handler's return value
     * @param propagatedContext The context the handler ran under
     * @return A flow completing once the value is sent
     */
    @SuppressWarnings("unchecked")
    private ExecutionFlow<Object> sendResult(ExecutableMethod<Object, ?> method,
                                             @Nullable Object result,
                                             PropagatedContext propagatedContext) {
        if (result == null || Publishers.isConvertibleToPublisher(result)) {
            return toFlow(result, propagatedContext);
        }
        if (result instanceof CompletionStage<?> stage) {
            return CompletableFutureExecutionFlow.just(((CompletionStage<Object>) stage).thenApply(value -> {
                if (value != null) {
                    sendReturnValue(method, value);
                }
                return null;
            }));
        }
        sendReturnValue(method, result);
        return ExecutionFlow.just(null);
    }

    /**
     * Sends a handler's return value: through a declared Jakarta encoder that accepts it, else
     * through Micronaut's message body writers, as the media type the handler {@code @Produces}
     * or JSON.
     */
    private void sendReturnValue(ExecutableMethod<Object, ?> method, Object value) {
        Object message = value;
        if (codecs != null && !codecs.isEmpty()) {
            try {
                message = codecs.encode(value);
            } catch (Exception e) {
                throw new WebSocketSessionException("Error encoding WebSocket message: " + e.getMessage(), e);
            }
        }
        MediaType mediaType = method.stringValue(Produces.class)
            .map(MediaType::of)
            .orElse(MediaType.APPLICATION_JSON_TYPE);
        micronautSession.sendSync(message, mediaType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ExecutionFlow<Object> toFlow(@Nullable Object result, PropagatedContext propagatedContext) {
        if (result == null) {
            return ExecutionFlow.just(null);
        }
        if (Publishers.isConvertibleToPublisher(result)) {
            // Subscribed eagerly: a lazy subscription would happen after the propagation
            // scope has exited, leaving the handler's reactive code without the request.
            return ReactiveExecutionFlow.fromPublisherEager(
                Publishers.convertToPublisher(support.conversionService(), result),
                propagatedContext
            );
        }
        if (result instanceof CompletionStage<?> stage) {
            return CompletableFutureExecutionFlow.just((CompletionStage<Object>) stage);
        }
        return ExecutionFlow.just(result);
    }

    private void handleClose(CloseReason reason, jakarta.websocket.CloseReason nativeReason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (gate) {
            // Messages held for an open handler that is still running die with the session.
            deferred = null;
        }
        closeRoute();
        ExecutableMethod<Object, ?> closeMethod = webSocketBean != null ? handler(webSocketBean.closeMethod()) : null;
        if (closeMethod == null) {
            finishClose();
            return;
        }
        invokeHandler(closeMethod, preBind(closeMethod, reason, nativeReason), false).onComplete((ignored, error) -> {
            if (error != null && LOG.isErrorEnabled()) {
                LOG.error("Error invoking @OnClose handler: {}", error.getMessage(), error);
            }
            finishClose();
        });
    }

    /**
     * The connection to a WebSocket route closed: nothing is sent after the close of the peer, the
     * stream of a message stream handler receives the messages read before the close and then
     * completes, before the close handler runs, which may wait for it; the other messages that
     * wait for a handler are discarded.
     */
    private void closeRoute() {
        RouteMessageQueue messages = routeMessages;
        WebSocketRouteEndpoint endpoint = routeEndpoint;
        if (messages == null || endpoint == null) {
            return;
        }
        if (micronautSession != null) {
            micronautSession.closeReceived();
        }
        List<Supplier<ExecutionFlow<?>>> waiting = messages.close();
        if (!endpoint.streamsMessages() || micronautSession == null) {
            return;
        }
        for (Supplier<ExecutionFlow<?>> message : waiting) {
            message.get();
        }
        try {
            endpoint.disconnected(micronautSession);
        } catch (RuntimeException e) {
            LOG.error("Error completing the WebSocket messages of [{}]: {}", endpoint, e.getMessage(), e);
        }
    }

    private void finishClose() {
        if (codecs != null) {
            codecs.destroy();
        }
        if (micronautSession != null) {
            sessionClosed(micronautSession, opened);
        }
    }

    /**
     * Offers a failure to {@code @OnError}, closing the connection when there is none or it fails
     * itself.
     *
     * @param cause The failure
     * @return A flow completing once the error handler has run
     */
    private ExecutionFlow<Object> forwardError(Throwable failure) {
        // a handler of a route fails with the failure of its stage, e.g. of a thenCompose
        Throwable cause = routeEndpoint != null && failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
        ExecutableMethod<Object, ?> errorMethod = webSocketBean != null ? handler(webSocketBean.errorMethod()) : null;
        if (errorMethod == null) {
            handleUnexpected(cause);
            return ExecutionFlow.just(null);
        }
        return invokeHandler(errorMethod, preBind(errorMethod, cause), false).onErrorResume(error -> {
            if (LOG.isErrorEnabled()) {
                LOG.error("Error invoking @OnError handler: {}", error.getMessage(), error);
            }
            // The original failure is what the connection has to be closed for.
            handleUnexpected(cause);
            return ExecutionFlow.just(null);
        });
    }

    private void handleUnexpected(Throwable cause) {
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains(CONNECTION_RESET)) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("WebSocket connection reset: {}", cause.getMessage(), cause);
            }
            return;
        }
        if (LOG.isErrorEnabled()) {
            LOG.error("Unexpected error in WebSocket handler: {}", cause.getMessage(), cause);
        }
        closeSession(CloseReason.INTERNAL_ERROR);
    }

    private void closeSession(CloseReason reason) {
        if (micronautSession != null) {
            micronautSession.close(reason);
        }
    }

    /**
     * Closes the container's session, ignoring a failure to do so: the connection is going away
     * anyway.
     *
     * @param session The container's session
     * @param reason  The close reason
     */
    protected static void closeQuietly(Session session, CloseReason reason) {
        try {
            session.close(new jakarta.websocket.CloseReason(
                jakarta.websocket.CloseReason.CloseCodes.getCloseCode(reason.getCode()),
                reason.getReason()
            ));
        } catch (Exception _) {
            // ignore, the connection is going away anyway
        }
    }

    /**
     * Matches each value against the first handler argument that can hold it and is not taken
     * yet, the way the Netty implementation pre-binds the close reason and the error cause.
     *
     * @param method The handler method
     * @param values The values to pre-bind; a {@code null} is skipped
     * @return The pre-bound arguments, empty when no argument accepts any value
     */
    static Map<Argument<?>, Object> preBind(ExecutableMethod<?, ?> method, @Nullable Object... values) {
        Map<Argument<?>, Object> preBound = null;
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            for (Argument<?> argument : method.getArguments()) {
                // An Object parameter is the message, never a framework value.
                if (argument.getType() != Object.class
                    && argument.getType().isInstance(value)
                    && (preBound == null || !preBound.containsKey(argument))) {
                    if (preBound == null) {
                        preBound = LinkedHashMap.newLinkedHashMap(values.length);
                    }
                    preBound.put(argument, value);
                    break;
                }
            }
        }
        return preBound == null ? Map.of() : preBound;
    }
}
