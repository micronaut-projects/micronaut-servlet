package io.micronaut.servlet.jetty.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;

@Requires(property = "spec.name", value = "JettyWebSocketSpec")
@ServerWebSocket("/ws/errors/message")
public class UnhandledErrorServerWebSocket {

    @OnMessage
    public void onMessage(String message) {
        throw new IllegalStateException("Bad things happened");
    }
}
