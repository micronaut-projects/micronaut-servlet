package io.micronaut.servlet.tomcat.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

@Requires(property = "spec.name", value = "TomcatWebSocketSpec")
@ClientWebSocket("/chat/{topic}/{username}")
public abstract class ChatClientWebSocket implements AutoCloseable {

    private WebSocketSession session;
    private HttpRequest<?> request;
    private String topic;
    private String username;
    private CloseReason closeReason;
    private final Collection<String> replies = new ConcurrentLinkedQueue<>();

    @OnOpen
    public void onOpen(String topic, String username, WebSocketSession session, HttpRequest<?> request) {
        this.topic = topic;
        this.username = username;
        this.session = session;
        this.request = request;
    }

    @OnMessage
    public void onMessage(String message) {
        replies.add(message);
    }

    @OnClose
    public void onClose(CloseReason closeReason) {
        this.closeReason = closeReason;
    }

    public abstract void send(String message);

    public String getTopic() {
        return topic;
    }

    public String getUsername() {
        return username;
    }

    public Collection<String> getReplies() {
        return replies;
    }

    public WebSocketSession getSession() {
        return session;
    }

    public HttpRequest<?> getRequest() {
        return request;
    }

    public CloseReason getCloseReason() {
        return closeReason;
    }
}
