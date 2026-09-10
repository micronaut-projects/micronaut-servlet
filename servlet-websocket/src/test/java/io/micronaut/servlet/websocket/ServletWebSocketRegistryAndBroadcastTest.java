package io.micronaut.servlet.websocket;

import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServletWebSocketRegistryAndBroadcastTest {

    private final ServletWebSocketSessionRegistry registry = new ServletWebSocketSessionRegistry();

    private ServletWebSocketSession register(TestSession nativeSession) {
        ServletWebSocketSession session = new ServletWebSocketSession(
            nativeSession,
            HttpRequest.GET("/chat"),
            ConvertibleValues.of(Map.of()),
            registry,
            new ServletWebSocketMessageEncoder(MessageBodyHandlerRegistry.EMPTY, ConversionService.SHARED),
            64
        );
        registry.register(session);
        return session;
    }

    @Test
    void onlyOpenSessionsAreVisible() {
        TestSession openSession = new TestSession();
        TestSession closedSession = new TestSession();
        register(openSession);
        register(closedSession);
        closedSession.open = false;

        assertEquals(1, registry.getOpenSessions().size());
        assertEquals(2, registry.getSessions().size());
    }

    @Test
    void deregisteringRemovesASession() {
        ServletWebSocketSession session = register(new TestSession());
        registry.deregister(session);

        assertTrue(registry.getOpenSessions().isEmpty());
    }

    @Test
    void serverShutdownClosesEveryOpenSession() {
        TestSession first = new TestSession();
        TestSession second = new TestSession();
        register(first);
        register(second);

        registry.onApplicationEvent(new ServerShutdownEvent(new NoOpEmbeddedServer()));

        assertEquals(CloseReason.GOING_AWAY.getCode(), first.closeReason.getCloseCode().getCode());
        assertEquals(CloseReason.GOING_AWAY.getCode(), second.closeReason.getCloseCode().getCode());
        assertTrue(registry.getSessions().isEmpty());
    }

    @Test
    void broadcastReachesEverySessionThePredicateAccepts() {
        TestSession included = new TestSession();
        TestSession excluded = new TestSession();
        register(included);
        ServletWebSocketSession excludedSession = register(excluded);
        ServletWebSocketBroadcaster broadcaster = new ServletWebSocketBroadcaster(registry);

        Mono<String> broadcast = Mono.from(
            broadcaster.<String>broadcast("hello", MediaType.TEXT_PLAIN_TYPE, s -> s != excludedSession)
        );
        broadcast.subscribe();

        assertEquals(1, included.sentText.size());
        assertEquals(0, excluded.sentText.size());
    }

    @Test
    void broadcastIsColdUntilSubscribed() {
        TestSession nativeSession = new TestSession();
        register(nativeSession);
        ServletWebSocketBroadcaster broadcaster = new ServletWebSocketBroadcaster(registry);

        broadcaster.broadcast("hello", MediaType.TEXT_PLAIN_TYPE, s -> true);

        assertTrue(nativeSession.sentText.isEmpty(), "nothing is written until the publisher is subscribed");
    }

    @Test
    void broadcastCompletesWithTheMessageWhenThereIsNobodyToSendTo() {
        ServletWebSocketBroadcaster broadcaster = new ServletWebSocketBroadcaster(registry);

        String result = Mono.from(broadcaster.<String>broadcast("hello", MediaType.TEXT_PLAIN_TYPE, s -> true)).block();

        assertEquals("hello", result);
    }

    @Test
    void aPeerThatWentAwayDuringTheBroadcastIsNotAFailure() {
        TestSession nativeSession = new TestSession();
        register(nativeSession);
        ServletWebSocketBroadcaster broadcaster = new ServletWebSocketBroadcaster(registry);

        Mono<String> broadcast = Mono.from(
            broadcaster.<String>broadcast("hello", MediaType.TEXT_PLAIN_TYPE, s -> true)
        );
        List<String> received = new java.util.ArrayList<>();
        broadcast.subscribe(received::add);

        nativeSession.open = false;
        nativeSession.failOldest(new java.io.IOException("peer went away"));

        assertEquals(List.of("hello"), received);
    }

    @Test
    void aFailureOnAStillOpenSessionIsReported() {
        TestSession nativeSession = new TestSession();
        register(nativeSession);
        ServletWebSocketBroadcaster broadcaster = new ServletWebSocketBroadcaster(registry);

        Mono<String> broadcast = Mono.from(
            broadcaster.<String>broadcast("hello", MediaType.TEXT_PLAIN_TYPE, s -> true)
        );
        List<Throwable> errors = new java.util.ArrayList<>();
        broadcast.subscribe(ignored -> { }, errors::add);

        nativeSession.failOldest(new IllegalStateException("broken"));

        assertFalse(errors.isEmpty(), "a live session that fails is a real broadcast failure");
    }

    @Test
    void broadcastingToAClosedSessionIsIgnored() {
        TestSession nativeSession = new TestSession();
        ServletWebSocketSession session = register(nativeSession);
        ServletWebSocketBroadcaster broadcaster = new ServletWebSocketBroadcaster(registry);
        assertThrows(Exception.class, () -> {
            nativeSession.open = false;
            session.sendAsync("direct", MediaType.TEXT_PLAIN_TYPE);
        });

        String result = Mono.from(broadcaster.<String>broadcast("hello", MediaType.TEXT_PLAIN_TYPE, s -> true)).block();

        assertEquals("hello", result);
    }

    private static final class NoOpEmbeddedServer implements EmbeddedServer {

        @Override
        public int getPort() {
            return 0;
        }

        @Override
        public String getHost() {
            return "localhost";
        }

        @Override
        public String getScheme() {
            return "http";
        }

        @Override
        public java.net.URL getURL() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.net.URI getURI() {
            return java.net.URI.create("http://localhost");
        }

        @Override
        public io.micronaut.context.ApplicationContext getApplicationContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.micronaut.runtime.ApplicationConfiguration getApplicationConfiguration() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public EmbeddedServer start() {
            return this;
        }

        @Override
        public EmbeddedServer stop() {
            return this;
        }
    }
}
