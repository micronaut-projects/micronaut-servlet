package io.micronaut.servlet.docs.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnMessage;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

@Requires(property = "spec.name", value = "EchoEndpointSpec")
@ClientWebSocket("/ws/echo/{room}")
public abstract class EchoClient implements AutoCloseable {

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
