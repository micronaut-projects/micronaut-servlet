package io.micronaut.servlet.undertow.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Receives text and binary frames alike as bytes, so one client covers both handlers.
 */
@Requires(property = "spec.name", value = "UndertowJakartaWebSocketSpec")
@ClientWebSocket
public abstract class JakartaEchoClient implements AutoCloseable {

    private WebSocketSession session;
    private CloseReason closeReason;
    private final Collection<String> replies = new ConcurrentLinkedQueue<>();

    @OnOpen
    public void onOpen(WebSocketSession session) {
        this.session = session;
    }

    @OnMessage
    public void onMessage(byte[] message) {
        replies.add(new String(message));
    }

    @OnClose
    public void onClose(CloseReason closeReason) {
        this.closeReason = closeReason;
    }

    public abstract void send(String message);

    public abstract void send(byte[] message);

    public WebSocketSession getSession() {
        return session;
    }

    public Collection<String> getReplies() {
        return replies;
    }

    public CloseReason getCloseReason() {
        return closeReason;
    }
}
