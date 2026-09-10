package io.micronaut.servlet.undertow.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnMessage;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

@Requires(property = "spec.name", value = "UndertowWebSocketSpec")
@ClientWebSocket("/pojo/chat/{topic}/{username}")
public abstract class PojoChatClientWebSocket implements AutoCloseable {

    private final Collection<Message> replies = new ConcurrentLinkedQueue<>();

    @OnMessage
    public void onMessage(Message message) {
        replies.add(message);
    }

    public abstract void send(Message message);

    public Collection<Message> getReplies() {
        return replies;
    }
}
