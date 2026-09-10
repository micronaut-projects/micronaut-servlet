package io.micronaut.servlet.tomcat.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;
import org.reactivestreams.Publisher;

@Requires(property = "spec.name", value = "TomcatWebSocketSpec")
@ServerWebSocket("/pojo/chat/{topic}/{username}")
public class PojoChatServerWebSocket {

    @OnMessage
    public Publisher<Message> onMessage(String username, Message message, WebSocketSession session) {
        return session.send(new Message("[" + username + "] " + message.getText()));
    }
}
