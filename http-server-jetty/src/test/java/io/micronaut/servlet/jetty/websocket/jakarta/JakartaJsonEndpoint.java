package io.micronaut.servlet.jetty.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.websocket.OnMessage;
import jakarta.websocket.server.ServerEndpoint;

/**
 * No decoder and no encoder declared: the message is read and the reply written by Micronaut's
 * message body handlers, as JSON, exactly as for a Micronaut endpoint.
 */
@Requires(property = "spec.name", value = "JettyJakartaWebSocketSpec")
@ServerEndpoint("/jakarta/json")
public class JakartaJsonEndpoint {

    @Serdeable
    public record Order(String item, int quantity) {
    }

    @Serdeable
    public record Confirmation(String item, int quantity, String status) {
    }

    @OnMessage
    public Confirmation order(Order order) {
        return new Confirmation(order.item(), order.quantity(), "confirmed");
    }
}
