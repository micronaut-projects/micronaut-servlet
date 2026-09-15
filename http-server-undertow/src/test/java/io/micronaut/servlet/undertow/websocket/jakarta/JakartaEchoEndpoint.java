package io.micronaut.servlet.undertow.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Jakarta tutorial's shape: per-connection state in fields, a static peer set, the raw
 * {@link Session} used to send, and a return value that is sent back.
 */
@Requires(property = "spec.name", value = "UndertowJakartaWebSocketSpec")
@ServerEndpoint(value = "/jakarta/echo/{room}", subprotocols = "echo")
public class JakartaEchoEndpoint {

    public static final Set<Session> PEERS = ConcurrentHashMap.newKeySet();
    public static final AtomicInteger INSTANCES = new AtomicInteger();
    public static volatile CloseReason lastClose;
    public static volatile Throwable lastError;
    public static volatile String lastPong;
    public static volatile boolean contextLeaked;

    private Session session;
    private String room;

    public JakartaEchoEndpoint() {
        INSTANCES.incrementAndGet();
    }

    @OnOpen
    public void open(Session session, EndpointConfig config, @PathParam("room") String roomName) throws IOException {
        this.session = session;
        this.room = roomName;
        contextLeaked = session.getUserProperties().containsKey("io.micronaut.servlet.websocket.CONTEXT")
            || config.getUserProperties().containsKey("io.micronaut.servlet.websocket.CONTEXT");
        PEERS.add(session);
        session.getBasicRemote().sendText("joined " + room);
    }

    @OnMessage(maxMessageSize = 1024)
    public String text(String message) {
        if ("fail".equals(message)) {
            throw new IllegalStateException("asked to fail");
        }
        return room + ": " + message;
    }

    @OnMessage
    public void binary(ByteBuffer data, Session s) throws IOException {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        if ("ping".equals(new String(bytes))) {
            s.getBasicRemote().sendPing(ByteBuffer.wrap("pong-data".getBytes()));
        } else {
            s.getAsyncRemote().sendBinary(ByteBuffer.wrap(bytes));
        }
    }

    @OnMessage
    public void pong(PongMessage pong) {
        ByteBuffer data = pong.getApplicationData();
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        lastPong = new String(bytes);
    }

    @OnClose
    public void close(Session session, CloseReason reason) {
        PEERS.remove(session);
        lastClose = reason;
    }

    @OnError
    public void error(Throwable t) {
        lastError = t;
    }
}
