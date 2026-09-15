package io.micronaut.servlet.websocket;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.websocket.annotation.ServerWebSocket;
import jakarta.inject.Singleton;
import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;
import jakarta.websocket.OnMessage;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JakartaEndpointTest {

    static ApplicationContext context;

    @BeforeAll
    static void start() {
        context = ApplicationContext.run(Map.of("test.name", "JakartaEndpointTest"));
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @Test
    void theHandlersOfAJakartaEndpointAreClassifiedByMessageCategory() {
        BeanDefinition<Full> definition = context.getBeanDefinition(Full.class);

        JakartaEndpoint endpoint = JakartaEndpoint.of(definition);

        assertNotNull(endpoint);
        assertEquals("text", endpoint.textMethod().getMethodName());
        assertEquals("binary", endpoint.binaryMethod().getMethodName());
        assertEquals("pong", endpoint.pongMethod().getMethodName());
        assertEquals(List.of(Full.WordDecoder.class), endpoint.decoders());
        assertEquals(List.of(Full.WordEncoder.class), endpoint.encoders());
        assertEquals(Full.Configurator.class, endpoint.configurator());
        assertEquals(Prototype.class.getName(), definition.getScopeName().orElseThrow());
    }

    @Test
    void anObjectMessageWithABinaryDecoderIsTheBinaryHandler() {
        JakartaEndpoint endpoint = JakartaEndpoint.of(context.getBeanDefinition(BinaryDecoded.class));

        assertNotNull(endpoint);
        assertEquals("text", endpoint.textMethod().getMethodName());
        assertEquals("frame", endpoint.binaryMethod().getMethodName());
        assertNull(endpoint.pongMethod());
    }

    @Test
    void aBoundParameterNextToThePayloadIsNeverTakenForTheMessage() {
        JakartaEndpoint endpoint = JakartaEndpoint.of(context.getBeanDefinition(Bound.class));

        assertNotNull(endpoint);
        assertEquals("binary", endpoint.binaryMethod().getMethodName());
        assertEquals("text", endpoint.textMethod().getMethodName());
    }

    @Test
    void theDefaultConfiguratorIsNotAComponent() {
        JakartaEndpoint endpoint = JakartaEndpoint.of(context.getBeanDefinition(BinaryDecoded.class));

        assertNotNull(endpoint);
        assertNull(endpoint.configurator());
        assertTrue(endpoint.encoders().isEmpty());
    }

    @Test
    void aMicronautEndpointIsNotAJakartaOne() {
        assertNull(JakartaEndpoint.of(context.getBeanDefinition(Micronaut.class)));
    }

    @Requires(property = "test.name", value = "JakartaEndpointTest")
    @Introspected(classes = Full.Configurator.class)
    @ServerEndpoint(value = "/full", decoders = Full.WordDecoder.class, encoders = Full.WordEncoder.class, configurator = Full.Configurator.class)
    static class Full {
        record Word(String value) {
        }

        @Singleton
        static class WordDecoder implements Decoder.Text<Word> {
            @Override
            public Word decode(String s) {
                return new Word(s);
            }

            @Override
            public boolean willDecode(String s) {
                return true;
            }
        }

        @Introspected
        static class WordEncoder implements Encoder.Text<Word> {
            @Override
            public String encode(Word word) {
                return word.value();
            }
        }

        public static class Configurator extends ServerEndpointConfig.Configurator {
        }

        @OnMessage
        public void text(String message, Session session) {
        }

        @OnMessage
        public void binary(byte[] message) {
        }

        @OnMessage
        public void pong(PongMessage pong) {
        }
    }

    @Requires(property = "test.name", value = "JakartaEndpointTest")
    @ServerEndpoint(value = "/binary", decoders = BinaryDecoded.FrameDecoder.class)
    static class BinaryDecoded {
        record Frame(byte[] bytes) {
        }

        @Singleton
        static class FrameDecoder implements Decoder.Binary<Frame> {
            @Override
            public Frame decode(ByteBuffer bytes) {
                return new Frame(new byte[0]);
            }

            @Override
            public boolean willDecode(ByteBuffer bytes) {
                return true;
            }
        }

        @OnMessage
        public void text(String message) {
        }

        @OnMessage
        public void frame(Frame frame) {
        }
    }

    @Requires(property = "test.name", value = "JakartaEndpointTest")
    @ServerEndpoint("/bound/{id}/{last}")
    static class Bound {
        @OnMessage
        public void binary(@PathParam("id") String id, ByteBuffer data) {
        }

        @OnMessage
        public void text(@PathParam("last") boolean last, String message) {
        }
    }

    @Requires(property = "test.name", value = "JakartaEndpointTest")
    @ServerWebSocket("/micronaut")
    static class Micronaut {
        @io.micronaut.websocket.annotation.OnMessage
        public void message(String message) {
        }
    }
}
