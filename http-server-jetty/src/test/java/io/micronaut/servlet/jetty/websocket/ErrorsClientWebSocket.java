package io.micronaut.servlet.jetty.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;

@Requires(property = "spec.name", pattern = "JettyWebSocketSpec|JettyWebSocketOriginSpec")
@ClientWebSocket
public abstract class ErrorsClientWebSocket implements AutoCloseable {

    private CloseReason closeReason;

    @OnClose
    public void onClose(CloseReason closeReason) {
        this.closeReason = closeReason;
    }

    @OnMessage
    public void onMessage(String message) {
    }

    public abstract void send(String message);

    public CloseReason getCloseReason() {
        return closeReason;
    }
}
