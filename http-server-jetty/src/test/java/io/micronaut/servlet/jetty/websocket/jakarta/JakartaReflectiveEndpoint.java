package io.micronaut.servlet.jetty.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.websocket.Decoder;
import jakarta.websocket.OnMessage;
import jakarta.websocket.server.ServerEndpoint;

/**
 * A decoder whose constructor takes an argument without being a bean: the generated
 * introspection cannot instantiate it, it compiles because {@code micronaut-reflection} is on
 * the classpath, and it resolves - with its argument injected - only when the application allows
 * the type.
 */
@Requires(property = "spec.name", value = "JettyJakartaWebSocketSpec")
@ServerEndpoint(value = "/jakarta/reflective", decoders = JakartaReflectiveEndpoint.PlainDecoder.class)
public class JakartaReflectiveEndpoint {

    public record Shout(String text) {
    }

    public static class PlainDecoder implements Decoder.Text<Shout> {
        private final Environment environment;

        public PlainDecoder(Environment environment) {
            this.environment = environment;
        }

        @Override
        public Shout decode(String s) {
            return new Shout(s.toUpperCase() + "!" + (environment != null ? "" : "?"));
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
