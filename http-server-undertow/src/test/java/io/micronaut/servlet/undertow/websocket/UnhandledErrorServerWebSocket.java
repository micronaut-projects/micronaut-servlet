package io.micronaut.servlet.undertow.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;

@Requires(property = "spec.name", value = "UndertowWebSocketSpec")
@ServerWebSocket("/ws/errors/message")
public class UnhandledErrorServerWebSocket {

    @OnMessage
    public void onMessage(String message) {
        throw new IllegalStateException("Bad things happened");
    }
}
