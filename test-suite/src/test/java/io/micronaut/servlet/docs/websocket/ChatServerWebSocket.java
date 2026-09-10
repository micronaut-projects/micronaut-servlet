package io.micronaut.servlet.docs.websocket;

// tag::imports[]
import io.micronaut.websocket.WebSocketBroadcaster;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;

import java.util.function.Predicate;
// end::imports[]

import io.micronaut.context.annotation.Requires;

@Requires(property = "spec.name", value = "ChatWebSocketSpec")
// tag::class[]
@ServerWebSocket("/ws/chat/{topic}/{username}") // <1>
public class ChatServerWebSocket {

    private final WebSocketBroadcaster broadcaster;

    public ChatServerWebSocket(WebSocketBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @OnOpen // <2>
    public void onOpen(String topic, String username, WebSocketSession session) {
        broadcaster.broadcastSync("[" + username + "] Joined!", isValid(topic, session));
    }

    @OnMessage // <3>
    public void onMessage(String topic, String username, String message, WebSocketSession session) {
        broadcaster.broadcastSync("[" + username + "] " + message, isValid(topic, session)); // <4>
    }

    @OnClose // <5>
    public void onClose(String topic, String username, WebSocketSession session) {
        broadcaster.broadcastSync("[" + username + "] Disconnected!", isValid(topic, session));
    }

    private Predicate<WebSocketSession> isValid(String topic, WebSocketSession session) {
        return s -> s != session && topic.equalsIgnoreCase(s.getUriVariables().get("topic", String.class, null));
    }
}
// end::class[]
