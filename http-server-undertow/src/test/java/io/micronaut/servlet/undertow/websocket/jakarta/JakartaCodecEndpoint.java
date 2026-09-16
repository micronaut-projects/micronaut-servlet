package io.micronaut.servlet.undertow.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;
import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.List;

/**
 * Decoders, encoders and a configurator resolved without reflection: the decoder is a bean, the
 * encoder is introspected, the configurator is listed on the endpoint's {@code @Introspected}.
 */
@Requires(property = "spec.name", value = "UndertowJakartaWebSocketSpec")
@Introspected(classes = JakartaCodecEndpoint.GreetingConfigurator.class)
@ServerEndpoint(
    value = "/jakarta/codec",
    decoders = JakartaCodecEndpoint.CommandDecoder.class,
    encoders = JakartaCodecEndpoint.ReplyEncoder.class,
    configurator = JakartaCodecEndpoint.GreetingConfigurator.class
)
public class JakartaCodecEndpoint {

    public static volatile boolean decoderInitialized;
    public static volatile boolean decoderDestroyed;

    public record Command(String verb, String argument) {
    }

    public record Reply(String text) {
    }

    @Singleton
    public static class CommandDecoder implements Decoder.Text<Command> {
        @Override
        public Command decode(String s) {
            String[] parts = s.split(" ", 2);
            return new Command(parts[0], parts.length > 1 ? parts[1] : "");
        }

        @Override
        public boolean willDecode(String s) {
            return s.contains(" ");
        }

        @Override
        public void init(EndpointConfig config) {
            decoderInitialized = true;
        }

        @Override
        public void destroy() {
            decoderDestroyed = true;
        }
    }

    @Introspected
    public static class ReplyEncoder implements Encoder.Text<Reply> {
        @Override
        public String encode(Reply reply) {
            return "reply:" + reply.text();
        }
    }

    public static class GreetingConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            sec.getUserProperties().put("greeting", "hello from the configurator");
            response.getHeaders().put("X-Configured", List.of("true"));
        }
    }

    @OnOpen
    public void open(Session session, EndpointConfig config) throws Exception {
        session.getBasicRemote().sendText(String.valueOf(config.getUserProperties().get("greeting")));
    }

    @OnMessage
    public Reply command(Command command) {
        return new Reply(switch (command.verb()) {
            case "shout" -> command.argument().toUpperCase();
            case "whisper" -> command.argument().toLowerCase();
            default -> "?";
        });
    }
}
