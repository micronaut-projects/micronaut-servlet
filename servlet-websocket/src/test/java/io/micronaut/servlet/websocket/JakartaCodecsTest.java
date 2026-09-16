package io.micronaut.servlet.websocket;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.type.Argument;
import jakarta.inject.Singleton;
import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnMessage;
import jakarta.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JakartaCodecsTest {

    @Test
    void decodersAreTriedInOrderAndTheResultHasToMatchTheParameter() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.name", "JakartaCodecsTest"))) {
            JakartaCodecs codecs = codecs(context);

            assertEquals(new Upper("HI"), codecs.decodeText("hi", Argument.of(Upper.class)));
            assertEquals(new Lower("hi"), codecs.decodeText("HI", Argument.of(Lower.class)));
            assertNull(codecs.decodeText("hi", Argument.of(Integer.class)), "no decoder produces an Integer");
            assertEquals(new Size(3), codecs.decodeBinary(new byte[3], Argument.of(Size.class)));
            assertNull(codecs.decodeBinary(new byte[3], Argument.of(Upper.class)), "a binary message is not offered to text decoders");
        }
    }

    @Test
    void theEncoderWhoseTypeTheValueIsAnInstanceOfEncodesIt() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.name", "JakartaCodecsTest"))) {
            JakartaCodecs codecs = codecs(context);

            assertEquals("upper:HI", codecs.encode(new Upper("HI")));
            assertEquals("size=3", codecs.encode(new Size(3)));
            Object untouched = new Lower("x");
            assertSame(untouched, codecs.encode(untouched), "a value no encoder accepts is sent as it is");
        }
    }

    @Test
    void anEncoderWhoseTypeIsUnknownStandsAsideForAValueItCannotEncode() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.name", "JakartaCodecsTest"))) {
            // Without the reflective route the introspected encoders' type arguments are unknown.
            JakartaEndpointComponents components = new JakartaEndpointComponents(context, null);
            JakartaEndpoint endpoint = JakartaEndpoint.of(context.getBeanDefinition(CodecEndpoint.class));
            JakartaCodecs codecs = JakartaCodecs.create(endpoint, components, endpointConfig());

            assertEquals("upper:HI", codecs.encode(new Upper("HI")));
            assertEquals("size=3", codecs.encode(new Size(3)), "the Upper encoder is tried first and stands aside");
        }
    }

    @Test
    void codecsAreInitializedOnCreationAndDestroyedOnce() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.name", "JakartaCodecsTest"))) {
            UpperDecoder.initialized = false;
            UpperDecoder.destroyed = false;
            JakartaCodecs codecs = codecs(context);

            assertTrue(UpperDecoder.initialized);
            codecs.destroy();
            assertTrue(UpperDecoder.destroyed);
        }
    }

    @Test
    void aCodecThatFailsToInitializeTakesTheOnesAlreadyInitializedDownWithIt() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.name", "JakartaCodecsTest"))) {
            UpperDecoder.destroyed = false;
            JakartaEndpoint endpoint = JakartaEndpoint.of(context.getBeanDefinition(FailingCodecEndpoint.class));

            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> JakartaCodecs.create(endpoint, context.getBean(JakartaEndpointComponents.class), endpointConfig()));

            assertEquals("cannot init", e.getMessage());
            assertTrue(UpperDecoder.destroyed, "the decoder initialized before the failing one is destroyed");
        }
    }

    private static JakartaCodecs codecs(ApplicationContext context) {
        JakartaEndpoint endpoint = JakartaEndpoint.of(context.getBeanDefinition(CodecEndpoint.class));
        return JakartaCodecs.create(endpoint, context.getBean(JakartaEndpointComponents.class), endpointConfig());
    }

    private static EndpointConfig endpointConfig() {
        Map<String, Object> userProperties = new HashMap<>();
        return (EndpointConfig) Proxy.newProxyInstance(
            JakartaCodecsTest.class.getClassLoader(),
            new Class<?>[]{EndpointConfig.class},
            (InvocationHandler) (proxy, method, args) ->
                method.getName().equals("getUserProperties") ? userProperties : List.of());
    }

    record Upper(String text) {
    }

    record Lower(String text) {
    }

    record Size(int bytes) {
    }

    @Singleton
    @Requires(property = "test.name", value = "JakartaCodecsTest")
    static class UpperDecoder implements Decoder.Text<Upper> {
        static volatile boolean initialized;
        static volatile boolean destroyed;

        @Override
        public Upper decode(String s) {
            return new Upper(s.toUpperCase());
        }

        @Override
        public boolean willDecode(String s) {
            return true;
        }

        @Override
        public void init(EndpointConfig config) {
            initialized = true;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }

    @Introspected
    static class LowerDecoder implements Decoder.Text<Lower> {
        @Override
        public Lower decode(String s) {
            return new Lower(s.toLowerCase());
        }

        @Override
        public boolean willDecode(String s) {
            return true;
        }
    }

    @Introspected
    static class SizeDecoder implements Decoder.BinaryStream<Size> {
        @Override
        public Size decode(InputStream is) throws java.io.IOException {
            return new Size(is.readAllBytes().length);
        }
    }

    @Introspected
    static class UpperEncoder implements Encoder.Text<Upper> {
        @Override
        public String encode(Upper upper) {
            return "upper:" + upper.text();
        }
    }

    @Introspected
    static class SizeEncoder implements Encoder.TextStream<Size> {
        @Override
        public void encode(Size size, Writer writer) throws java.io.IOException {
            writer.write("size=" + size.bytes());
        }
    }

    public static class FailingDecoder implements Decoder.Text<Lower> {
        @Override
        public Lower decode(String s) {
            return new Lower(s);
        }

        @Override
        public boolean willDecode(String s) {
            return true;
        }

        @Override
        public void init(EndpointConfig config) {
            throw new IllegalStateException("cannot init");
        }
    }

    @Requires(property = "test.name", value = "JakartaCodecsTest")
    @ServerEndpoint(value = "/failing-codecs", decoders = {UpperDecoder.class, FailingDecoder.class})
    static class FailingCodecEndpoint {
        @OnMessage
        public void text(Upper upper) {
            // only the signature matters: the test reads how the handler is classified
        }
    }

    @Requires(property = "test.name", value = "JakartaCodecsTest")
    @ServerEndpoint(
        value = "/codecs",
        decoders = {UpperDecoder.class, LowerDecoder.class, SizeDecoder.class},
        encoders = {UpperEncoder.class, SizeEncoder.class}
    )
    static class CodecEndpoint {
        @OnMessage
        public void text(Upper upper) {
            // only the signature matters: the test reads how the handler is classified
        }

        @OnMessage
        public void binary(ByteBuffer bytes) {
            // only the signature matters: the test reads how the handler is classified
        }
    }
}
