package io.micronaut.servlet.docs.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnMessage;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

@Requires(property = "spec.name", value = "ChatWebSocketSpec")
// tag::class[]
@ClientWebSocket("/ws/chat/{topic}/{username}") // <1>
public abstract class ChatClientWebSocket implements AutoCloseable { // <2>

    private final Collection<String> replies = new ConcurrentLinkedQueue<>();

    @OnMessage
    public void onMessage(String message) {
        replies.add(message); // <3>
    }

    public abstract void send(String message); // <4>

    public Collection<String> getReplies() {
        return replies;
    }
}
// end::class[]
