package io.micronaut.servlet.websocket;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServletWebSocketConfigurationTest {

    @Test
    void theDefaultsMatchTheNettyServer() {
        ServletWebSocketConfiguration configuration = new ServletWebSocketConfiguration();

        assertTrue(configuration.isEnabled());
        assertEquals(ServletWebSocketConfiguration.DEFAULT_MAX_MESSAGE_SIZE, configuration.getMaxTextMessageSize());
        assertEquals(ServletWebSocketConfiguration.DEFAULT_MAX_MESSAGE_SIZE, configuration.getMaxBinaryMessageSize());
        assertEquals(ServletWebSocketConfiguration.DEFAULT_MAX_PENDING_SENDS, configuration.getMaxPendingSends());
        assertNull(configuration.getIdleTimeout(), "an unset idle timeout inherits micronaut.server.idle-timeout");
        assertNull(configuration.getAsyncSendTimeout());
        assertTrue(configuration.getCompression().isEnabled());
    }

    @Test
    void everyPropertyIsBindable() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "micronaut.servlet.websocket.enabled", true,
            "micronaut.servlet.websocket.max-text-message-size", 1024,
            "micronaut.servlet.websocket.max-binary-message-size", 2048,
            "micronaut.servlet.websocket.idle-timeout", "30s",
            "micronaut.servlet.websocket.async-send-timeout", "5s",
            "micronaut.servlet.websocket.max-pending-sends", 8,
            "micronaut.servlet.websocket.compression.enabled", false
        ))) {
            ServletWebSocketConfiguration configuration = context.getBean(ServletWebSocketConfiguration.class);

            assertTrue(configuration.isEnabled());
            assertEquals(1024, configuration.getMaxTextMessageSize());
            assertEquals(2048, configuration.getMaxBinaryMessageSize());
            assertEquals(Duration.ofSeconds(30), configuration.getIdleTimeout());
            assertEquals(Duration.ofSeconds(5), configuration.getAsyncSendTimeout());
            assertEquals(8, configuration.getMaxPendingSends());
            assertFalse(configuration.getCompression().isEnabled());
        }
    }

    @Test
    void aNullCompressionBlockFallsBackToTheDefault() {
        ServletWebSocketConfiguration configuration = new ServletWebSocketConfiguration();
        configuration.setCompression(null);

        assertTrue(configuration.getCompression().isEnabled());
    }

    @Test
    void theConfiguredDefaultsAreAppliedToTheContainer() {
        ServletWebSocketConfiguration configuration = new ServletWebSocketConfiguration();
        configuration.setMaxTextMessageSize(111);
        configuration.setMaxBinaryMessageSize(222);
        configuration.setAsyncSendTimeout(Duration.ofSeconds(7));
        TestSession container = new TestSession();

        ServerContainerCustomizer.apply(new TestContainer(container), configuration, Duration.ofMinutes(2));

        assertEquals(111, container.maxTextMessageBufferSize);
        assertEquals(222, container.maxBinaryMessageBufferSize);
        assertEquals(Duration.ofMinutes(2).toMillis(), container.maxIdleTimeout);
    }

    /**
     * Records what {@link ServerContainerCustomizer} applies, by reusing the counters of the
     * session double.
     */
    private record TestContainer(TestSession recorder) implements jakarta.websocket.WebSocketContainer {

        @Override
        public long getDefaultAsyncSendTimeout() {
            return 0;
        }

        @Override
        public void setAsyncSendTimeout(long timeoutmillis) {
            // recorded by the assertions above only through the other setters
        }

        @Override
        public jakarta.websocket.Session connectToServer(Object endpoint, java.net.URI path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.websocket.Session connectToServer(Class<?> annotatedEndpointClass, java.net.URI path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.websocket.Session connectToServer(jakarta.websocket.Endpoint endpointInstance,
                                                         jakarta.websocket.ClientEndpointConfig cec,
                                                         java.net.URI path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.websocket.Session connectToServer(Class<? extends jakarta.websocket.Endpoint> endpointClass,
                                                         jakarta.websocket.ClientEndpointConfig cec,
                                                         java.net.URI path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getDefaultMaxSessionIdleTimeout() {
            return recorder.maxIdleTimeout;
        }

        @Override
        public void setDefaultMaxSessionIdleTimeout(long timeout) {
            recorder.maxIdleTimeout = timeout;
        }

        @Override
        public int getDefaultMaxBinaryMessageBufferSize() {
            return recorder.maxBinaryMessageBufferSize;
        }

        @Override
        public void setDefaultMaxBinaryMessageBufferSize(int max) {
            recorder.maxBinaryMessageBufferSize = max;
        }

        @Override
        public int getDefaultMaxTextMessageBufferSize() {
            return recorder.maxTextMessageBufferSize;
        }

        @Override
        public void setDefaultMaxTextMessageBufferSize(int max) {
            recorder.maxTextMessageBufferSize = max;
        }

        @Override
        public java.util.Set<jakarta.websocket.Extension> getInstalledExtensions() {
            return java.util.Set.of();
        }
    }
}
