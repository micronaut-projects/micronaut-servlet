package io.micronaut.servlet.tomcat.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.websocket.WebSocketBroadcaster;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;

import java.util.function.Predicate;

@Requires(property = "spec.name", value = "TomcatWebSocketSpec")
@ServerWebSocket("/chat/{topic}/{username}")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ChatServerWebSocket {

    private final WebSocketBroadcaster broadcaster;
    private String openThreadName;
    private String messageThreadName;
    private boolean openThreadVirtual;
    private boolean messageThreadVirtual;

    public ChatServerWebSocket(WebSocketBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @OnOpen
    public void onOpen(String topic, String username, WebSocketSession session) {
        this.openThreadName = Thread.currentThread().getName();
        this.openThreadVirtual = Thread.currentThread().isVirtual();
        assert ServerRequestContext.currentRequest().isPresent();
        broadcaster.broadcastSync("[" + username + "] Joined!", isValid(topic, session));
    }

    @OnMessage
    public void onMessage(String topic, String username, String message, WebSocketSession session) {
        this.messageThreadName = Thread.currentThread().getName();
        this.messageThreadVirtual = Thread.currentThread().isVirtual();
        broadcaster.broadcastSync("[" + username + "] " + message, isValid(topic, session));
    }

    @OnClose
    public void onClose(String topic, String username, WebSocketSession session) {
        broadcaster.broadcastSync("[" + username + "] Disconnected!", isValid(topic, session));
    }

    private Predicate<WebSocketSession> isValid(String topic, WebSocketSession session) {
        return s -> s != session && topic.equalsIgnoreCase(s.getUriVariables().get("topic", String.class, null));
    }

    public String getOpenThreadName() {
        return openThreadName;
    }

    public String getMessageThreadName() {
        return messageThreadName;
    }

    public boolean isOpenThreadVirtual() {
        return openThreadVirtual;
    }

    public boolean isMessageThreadVirtual() {
        return messageThreadVirtual;
    }

    /**
     * @return whether the handler ran somewhere it is safe to block, which is what
     * {@code @ExecuteOn(BLOCKING)} guarantees. Micronaut runs the handler inline when the
     * container thread already offers that guarantee, as Tomcat's virtual threads do.
     */
    public static boolean isBlockingSafe(String threadName, boolean virtual) {
        return virtual || threadName.startsWith("virtual-executor") || threadName.contains("io-executor");
    }
}
