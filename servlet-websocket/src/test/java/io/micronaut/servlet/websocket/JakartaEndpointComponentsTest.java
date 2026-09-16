package io.micronaut.servlet.websocket;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.websocket.exceptions.WebSocketException;
import jakarta.inject.Singleton;
import jakarta.websocket.Encoder;
import jakarta.websocket.OnMessage;
import jakarta.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JakartaEndpointComponentsTest {

    private static final String TEST = "JakartaEndpointComponentsTest";

    @Test
    void aBeanIsResolvedAsItsScopeSays() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.name", TEST))) {
            JakartaEndpointComponents components = context.getBean(JakartaEndpointComponents.class);

            assertSame(components.instantiate(SingletonCodec.class), components.instantiate(SingletonCodec.class));
            assertEquals(String.class, components.encodedType(SingletonCodec.class));
        }
    }

    @Test
    void anIntrospectedTypeIsInstantiatedAnew() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.name", TEST))) {
            JakartaEndpointComponents components = context.getBean(JakartaEndpointComponents.class);

            assertNotSame(components.instantiate(IntrospectedCodec.class), components.instantiate(IntrospectedCodec.class));
            assertEquals(Integer.class, components.encodedType(IntrospectedCodec.class));
        }
    }

    @Test
    void aPlainClassAnEndpointNamesIsInstantiatedThroughTheIntrospectionTheProcessorGenerated() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.name", TEST))) {
            JakartaEndpointComponents components = context.getBean(JakartaEndpointComponents.class);

            assertFalse(components.isReflectionAllowed(PlainCodec.class));
            PlainCodec first = components.instantiate(PlainCodec.class);
            assertInstanceOf(PlainCodec.class, first);
            assertNotSame(first, components.instantiate(PlainCodec.class), "one per endpoint instance");
        }
    }

    @Test
    void aClassWhoseConstructorTakesArgumentsIsRejectedUntilTheApplicationAllowsReflectionForIt() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.name", TEST))) {
            JakartaEndpointComponents components = context.getBean(JakartaEndpointComponents.class);

            WebSocketException e = assertThrows(WebSocketException.class, () -> components.instantiate(InjectedCodec.class));
            assertTrue(e.getMessage().contains(InjectedCodec.class.getName()));
            assertTrue(e.getMessage().contains("micronaut.introspection.allow-reflection"));
            assertFalse(components.isReflectionAllowed(InjectedCodec.class));
        }

        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "test.name", TEST,
            "micronaut.introspection.allow-reflection", List.of(InjectedCodec.class.getName())
        ))) {
            JakartaEndpointComponents components = context.getBean(JakartaEndpointComponents.class);

            assertTrue(components.isReflectionAllowed(InjectedCodec.class));
            InjectedCodec first = components.instantiate(InjectedCodec.class);
            assertSame(context.getBean(ConversionService.class), first.conversionService, "the constructor argument is injected");
            assertNotSame(first, components.instantiate(InjectedCodec.class), "a class without a scope is a prototype, one per endpoint instance");
            assertEquals(Long.class, components.encodedType(InjectedCodec.class));
        }
    }

    @Requires(property = "test.name", value = TEST)
    @Singleton
    static class SingletonCodec implements Encoder.Text<String> {
        @Override
        public String encode(String value) {
            return value;
        }
    }

    @Introspected
    static class IntrospectedCodec implements Encoder.Text<Integer> {
        @Override
        public String encode(Integer value) {
            return value.toString();
        }
    }

    public static class PlainCodec implements Encoder.Text<Long> {
        @Override
        public String encode(Long value) {
            return value.toString();
        }
    }

    public static class InjectedCodec implements Encoder.Text<Long> {
        final ConversionService conversionService;

        public InjectedCodec(ConversionService conversionService) {
            this.conversionService = conversionService;
        }

        @Override
        public String encode(Long value) {
            return value.toString();
        }
    }

    /**
     * Names the codecs, so the processor generates their introspections as it does for any endpoint.
     */
    @Requires(property = "test.name", value = TEST)
    @ServerEndpoint(value = "/codecs", encoders = {PlainCodec.class, InjectedCodec.class})
    static class CodecEndpoint {
        @OnMessage
        public Long text(String message) {
            return (long) message.length();
        }
    }
}
