package io.micronaut.servlet.undertow.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

@Requires(property = "spec.name", value = "UndertowWebSocketSpec")
@ClientWebSocket("/binary/chat/{username}")
public abstract class BinaryChatClientWebSocket implements AutoCloseable {

    private WebSocketSession session;
    private final Collection<String> replies = new ConcurrentLinkedQueue<>();

    @OnOpen
    public void onOpen(WebSocketSession session) {
        this.session = session;
    }

    @OnMessage
    public void onMessage(byte[] message) {
        replies.add(new String(message));
    }

    public abstract void send(byte[] message);

    public WebSocketSession getSession() {
        return session;
    }

    public Collection<String> getReplies() {
        return replies;
    }
}
