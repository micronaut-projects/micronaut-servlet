package io.micronaut.servlet.jetty.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;

@Requires(property = "spec.name", value = "JettyWebSocketSpec")
/**
 * Target of the filter cancellation test. Route authorization through micronaut-security
 * is covered separately by {@code JettyWebSocketSecuritySpec}.
 */
@ServerWebSocket("/filtered/chat")
public class FilteredChatServerWebSocket {

    @OnMessage
    public void onMessage(String message, WebSocketSession session) {
        session.sendSync(message);
    }
}
