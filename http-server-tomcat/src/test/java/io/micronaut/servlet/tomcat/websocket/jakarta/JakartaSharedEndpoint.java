package io.micronaut.servlet.tomcat.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A declared scope wins over the per-connection default.
 */
@Requires(property = "spec.name", value = "TomcatJakartaWebSocketSpec")
@Singleton
@ServerEndpoint("/jakarta/shared")
public class JakartaSharedEndpoint {

    public static final AtomicInteger INSTANCES = new AtomicInteger();

    private final AtomicInteger messages = new AtomicInteger();

    public JakartaSharedEndpoint() {
        INSTANCES.incrementAndGet();
    }

    @OnMessage
    public String count(String message, Session session) {
        return "message " + messages.incrementAndGet() + " on " + session.getRequestURI().getPath();
    }
}
