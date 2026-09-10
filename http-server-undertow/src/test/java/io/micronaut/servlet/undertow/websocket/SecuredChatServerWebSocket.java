package io.micronaut.servlet.undertow.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;

@Requires(property = "spec.name", value = "UndertowWebSocketSpec")
@ServerWebSocket("/secured/chat")
public class SecuredChatServerWebSocket {

    @OnMessage
    public void onMessage(String message, WebSocketSession session) {
        session.sendSync(message);
    }
}
