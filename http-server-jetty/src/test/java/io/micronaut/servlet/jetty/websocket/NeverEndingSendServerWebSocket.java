package io.micronaut.servlet.jetty.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;

/**
 * Sends a stream that never ends: its future completes once the peer closes.
 */
@Requires(property = "spec.name", value = "JettyWebSocketSpec")
@ServerWebSocket("/ws/never-ending-send")
public class NeverEndingSendServerWebSocket {

    private volatile CompletableFuture<?> sent;

    @OnOpen
    public void onOpen(WebSocketSession session) {
        sent = session.sendAllAsync(Flux.never());
    }

    @OnMessage
    public void onMessage(String message) {
        // nothing to answer
    }

    public CompletableFuture<?> getSent() {
        return sent;
    }
}
