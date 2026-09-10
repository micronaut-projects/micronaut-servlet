package io.micronaut.servlet.jetty.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnMessage;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

@Requires(property = "spec.name", value = "JettyWebSocketSecuritySpec")
@ClientWebSocket
public abstract class ChatReplyClientWebSocket implements AutoCloseable {

    private final Collection<String> replies = new ConcurrentLinkedQueue<>();

    @OnMessage
    public void onMessage(String message) {
        replies.add(message);
    }

    public abstract void send(String message);

    public Collection<String> getReplies() {
        return replies;
    }
}
