package io.micronaut.servlet.jetty.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;

/**
 * Declares a payload limit that happens to equal the annotation's own default. The endpoint
 * did declare it, so it has to outrank the much smaller configured size; a value read
 * straight from the annotation metadata could not be told apart from an unset one.
 */
@Requires(property = "spec.name", value = "JettyWebSocketOriginSpec")
@ServerWebSocket("/large/chat")
public class LargePayloadServerWebSocket {

    @OnMessage(maxPayloadLength = 65536)
    public void onMessage(String message, WebSocketSession session) {
        session.sendSync("received " + message.length());
    }
}
