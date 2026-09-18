package io.micronaut.servlet.undertow.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A Jakarta client, opened through the container the Undertow initializer registered.
 */
@Requires(property = "spec.name", value = "UndertowJakartaWebSocketSpec")
@ClientEndpoint(subprotocols = "echo")
public class JakartaEchoClientEndpoint {

    public final Queue<String> replies = new ConcurrentLinkedQueue<>();
    public volatile Session session;
    public volatile String negotiatedSubprotocol;
    public volatile CloseReason closeReason;

    @OnOpen
    public void open(Session session) {
        this.session = session;
        this.negotiatedSubprotocol = session.getNegotiatedSubprotocol();
    }

    @OnMessage
    public void text(String message) {
        replies.add(message);
    }

    @OnClose
    public void close(CloseReason reason) {
        this.closeReason = reason;
    }

    public void send(String message) throws IOException {
        session.getBasicRemote().sendText(message);
    }
}
