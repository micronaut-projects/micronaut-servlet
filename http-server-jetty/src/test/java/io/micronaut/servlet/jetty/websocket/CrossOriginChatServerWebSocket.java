package io.micronaut.servlet.jetty.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.server.cors.CrossOrigin;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;

/**
 * The endpoint states its own origin policy, which has to be honoured even when global CORS
 * is off, since a browser does not apply its CORS response check to a handshake.
 */
@Requires(property = "spec.name", value = "JettyWebSocketOriginSpec")
@CrossOrigin("https://trusted.example")
@ServerWebSocket("/cross-origin/chat")
public class CrossOriginChatServerWebSocket {

    @OnMessage
    public void onMessage(String message, WebSocketSession session) {
        session.sendSync(message);
    }
}
