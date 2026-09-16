package io.micronaut.servlet.jetty.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;

/**
 * A Micronaut endpoint for a Jakarta client to talk to.
 */
@Requires(property = "spec.name", value = "JettyJakartaWebSocketSpec")
@ServerWebSocket("/jakarta/chat/{name}")
public class JakartaChatServerWebSocket {

    @OnOpen
    public void open(String name, WebSocketSession session) {
        session.sendSync("welcome " + name);
    }

    @OnMessage
    public void message(String name, String message, WebSocketSession session) {
        session.sendSync(name + " said " + message);
    }
}
