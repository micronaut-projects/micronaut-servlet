package io.micronaut.servlet.jetty.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;

/**
 * A client whose {@code @OnOpen} fails: the connection must be reported as failed and closed.
 */
@Requires(property = "spec.name", value = "JettyJakartaWebSocketSpec")
@ClientEndpoint
public class JakartaFailingClientEndpoint {

    public static volatile Throwable lastError;

    @OnOpen
    public void open() {
        throw new IllegalStateException("refusing to open");
    }

    @OnMessage
    public void text(String message) {
        // never reached
    }

    @OnError
    public void error(Throwable error) {
        lastError = error;
    }
}
