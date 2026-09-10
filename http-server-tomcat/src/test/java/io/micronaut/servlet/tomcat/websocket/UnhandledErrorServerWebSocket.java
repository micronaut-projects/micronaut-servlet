package io.micronaut.servlet.tomcat.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;

@Requires(property = "spec.name", value = "TomcatWebSocketSpec")
@ServerWebSocket("/ws/errors/message")
public class UnhandledErrorServerWebSocket {

    @OnMessage
    public void onMessage(String message) {
        throw new IllegalStateException("Bad things happened");
    }
}
