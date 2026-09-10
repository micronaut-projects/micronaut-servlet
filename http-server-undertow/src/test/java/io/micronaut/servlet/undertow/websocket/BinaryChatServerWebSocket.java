package io.micronaut.servlet.undertow.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.WebSocketPongMessage;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;

import java.util.concurrent.atomic.AtomicReference;

@Requires(property = "spec.name", value = "UndertowWebSocketSpec")
@ServerWebSocket("/binary/chat/{username}")
public class BinaryChatServerWebSocket {

    private final AtomicReference<String> lastPong = new AtomicReference<>();

    @OnMessage
    public void onMessage(String username, byte[] message, WebSocketSession session) {
        String text = new String(message);
        if ("ping".equals(text)) {
            session.sendPingAsync("pong-data".getBytes());
            return;
        }
        session.sendSync(("[" + username + "] " + text).getBytes());
    }

    @OnMessage
    public void onPong(WebSocketPongMessage message) {
        lastPong.set(new String(message.getContent().toByteArray()));
    }

    public String getLastPong() {
        return lastPong.get();
    }
}
