package io.micronaut.servlet.jetty.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import jakarta.websocket.Decoder;
import jakarta.websocket.OnMessage;
import jakarta.websocket.server.ServerEndpoint;

/**
 * A decoder that is neither a bean nor introspected: it compiles because {@code micronaut-reflection}
 * is on the classpath, and resolves only when the application allows the type.
 */
@Requires(property = "spec.name", value = "JettyJakartaWebSocketSpec")
@ServerEndpoint(value = "/jakarta/reflective", decoders = JakartaReflectiveEndpoint.PlainDecoder.class)
public class JakartaReflectiveEndpoint {

    public record Shout(String text) {
    }

    public static class PlainDecoder implements Decoder.Text<Shout> {
        @Override
        public Shout decode(String s) {
            return new Shout(s.toUpperCase() + "!");
        }

        @Override
        public boolean willDecode(String s) {
            return true;
        }
    }

    @OnMessage
    public String shout(Shout shout) {
        return shout.text();
    }
}
