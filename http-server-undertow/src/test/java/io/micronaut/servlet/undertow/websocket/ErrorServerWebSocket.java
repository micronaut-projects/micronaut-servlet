package io.micronaut.servlet.undertow.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnError;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;

@Requires(property = "spec.name", value = "UndertowWebSocketSpec")
@ServerWebSocket("/ws/errors/message-onerror")
public class ErrorServerWebSocket {

    @OnMessage
    public void onMessage(String message, WebSocketSession session) {
        throw new IllegalStateException("Bad things happened");
    }

    @OnError
    public void onError(Throwable error, WebSocketSession session) {
        session.close(new CloseReason(CloseReason.UNSUPPORTED_DATA.getCode(), error.getMessage()));
    }
}
