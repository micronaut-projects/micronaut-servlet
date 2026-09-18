package io.micronaut.servlet.jetty.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Header;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Jakarta tutorial's client shape: a concrete class that keeps the session in a field,
 * sends through it, and has a {@code send} method of its own that must stay its own.
 */
@Requires(property = "spec.name", value = "JettyJakartaWebSocketSpec")
@ClientEndpoint(subprotocols = "echo", configurator = JakartaEchoClientEndpoint.HeaderConfigurator.class)
public class JakartaEchoClientEndpoint {

    public static final AtomicInteger INSTANCES = new AtomicInteger();

    public final Queue<String> replies = new ConcurrentLinkedQueue<>();
    public final Queue<String> binaryReplies = new ConcurrentLinkedQueue<>();
    public volatile Session session;
    public volatile String negotiatedSubprotocol;
    public volatile String clientHeader;
    public volatile String lastPong;
    public volatile CloseReason closeReason;
    public volatile Throwable error;

    public JakartaEchoClientEndpoint() {
        INSTANCES.incrementAndGet();
    }

    public static class HeaderConfigurator extends ClientEndpointConfig.Configurator {
        public static volatile boolean afterResponse;

        @Override
        public void beforeRequest(Map<String, List<String>> headers) {
            headers.put("X-Client", List.of("jakarta"));
        }

        @Override
        public void afterResponse(HandshakeResponse response) {
            afterResponse = true;
        }
    }

    @OnOpen
    public void open(Session session, EndpointConfig config, @Header("X-Client") String clientHeader) {
        this.session = session;
        this.negotiatedSubprotocol = session.getNegotiatedSubprotocol();
        this.clientHeader = clientHeader;
    }

    @OnMessage
    public String text(String message) {
        replies.add(message);
        // The value a client handler returns is sent to the server, as on the server side.
        return message.endsWith(": ack") ? "client-ack" : null;
    }

    @OnMessage
    public void binary(ByteBuffer data) {
        binaryReplies.add(StandardCharsets.UTF_8.decode(data).toString());
    }

    @OnMessage
    public void pong(PongMessage pong) {
        lastPong = StandardCharsets.UTF_8.decode(pong.getApplicationData()).toString();
    }

    @OnClose
    public void close(CloseReason reason) {
        this.closeReason = reason;
    }

    @OnError
    public void error(Throwable error) {
        this.error = error;
    }

    /**
     * A method of the endpoint's own that happens to start with {@code send}: no interceptor
     * may take it over.
     */
    public void send(String message) throws IOException {
        session.getBasicRemote().sendText(message);
    }

    public void close() throws IOException {
        session.close();
    }
}
