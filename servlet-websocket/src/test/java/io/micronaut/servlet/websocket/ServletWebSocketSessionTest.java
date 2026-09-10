package io.micronaut.servlet.websocket;

import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.exceptions.WebSocketSessionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServletWebSocketSessionTest {

    private TestSession nativeSession;
    private ServletWebSocketSessionRegistry registry;
    private ServletWebSocketSession session;

    @BeforeEach
    void setUp() {
        nativeSession = new TestSession();
        registry = new ServletWebSocketSessionRegistry();
        session = newSession(4);
        registry.register(session);
    }

    private ServletWebSocketSession newSession(int maxPendingSends) {
        ConversionService conversionService = ConversionService.SHARED;
        return new ServletWebSocketSession(
            nativeSession,
            HttpRequest.GET("/chat/football/fred"),
            ConvertibleValues.of(Map.of("topic", "football")),
            registry,
            new ServletWebSocketMessageEncoder(MessageBodyHandlerRegistry.EMPTY, conversionService),
            maxPendingSends
        );
    }

    @Test
    void onlyOneSendIsOutstandingOnTheContainerAtATime() {
        CompletableFuture<String> first = session.sendAsync("one", MediaType.TEXT_PLAIN_TYPE);
        CompletableFuture<String> second = session.sendAsync("two", MediaType.TEXT_PLAIN_TYPE);
        CompletableFuture<String> third = session.sendAsync("three", MediaType.TEXT_PLAIN_TYPE);

        assertEquals(1, nativeSession.inFlight.get(), "the container must never see two sends at once");
        assertEquals(1, nativeSession.sentText.size());
        assertFalse(first.isDone());

        assertTrue(nativeSession.completeOldest());
        assertTrue(first.isDone());
        assertEquals("one", first.join());
        assertEquals(1, nativeSession.inFlight.get());
        assertEquals(2, nativeSession.sentText.size());

        assertTrue(nativeSession.completeOldest());
        assertTrue(nativeSession.completeOldest());
        assertTrue(second.isDone());
        assertTrue(third.isDone());
        assertEquals(0, nativeSession.inFlight.get());
        assertEquals(3, nativeSession.sentText.size());
    }

    @Test
    void aFailedSendFailsOnlyThatMessageAndTheQueueKeepsDraining() {
        CompletableFuture<String> first = session.sendAsync("one", MediaType.TEXT_PLAIN_TYPE);
        CompletableFuture<String> second = session.sendAsync("two", MediaType.TEXT_PLAIN_TYPE);

        nativeSession.failOldest(new IllegalStateException("boom"));

        assertTrue(first.isCompletedExceptionally());
        assertFalse(second.isDone());
        assertTrue(nativeSession.completeOldest());
        assertEquals("two", second.join());
    }

    @Test
    void isWritableReportsTheQueueDepth() {
        assertTrue(session.isWritable());
        for (int i = 0; i < 6; i++) {
            session.sendAsync("message " + i, MediaType.TEXT_PLAIN_TYPE);
        }
        assertFalse(session.isWritable(), "queued sends beyond the limit make the session unwritable");

        while (nativeSession.completeOldest()) {
            // drain
        }
        assertTrue(session.isWritable());
    }

    @Test
    void binaryPayloadsAreSentAsBinaryFrames() {
        session.sendAsync("hello".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_OCTET_STREAM_TYPE);

        assertEquals(1, nativeSession.sentBinary.size());
        assertTrue(nativeSession.sentText.isEmpty());
    }

    @Test
    void pingsGoThroughTheSameQueue() {
        CompletableFuture<?> ping = session.sendPingAsync("ping-data".getBytes(StandardCharsets.UTF_8));

        assertTrue(ping.isDone(), "a ping completes as soon as the container accepts it");
        assertEquals(1, nativeSession.sentPings.size());
    }

    @Test
    void sendingOnAClosedSessionIsRejected() {
        nativeSession.open = false;

        assertThrows(WebSocketSessionException.class, () -> session.sendAsync("nope", MediaType.TEXT_PLAIN_TYPE));
        assertThrows(WebSocketSessionException.class, () -> session.sendPingAsync(new byte[0]));
    }

    @Test
    void aNullMessageCompletesWithoutTouchingTheContainer() {
        assertTrue(session.sendAsync(null, MediaType.TEXT_PLAIN_TYPE).isDone());
        assertTrue(nativeSession.sentText.isEmpty());
    }

    @Test
    void closingMapsTheCloseReasonAndDeregisters() {
        session.close(CloseReason.GOING_AWAY);

        assertNotNull(nativeSession.closeReason);
        assertEquals(CloseReason.GOING_AWAY.getCode(), nativeSession.closeReason.getCloseCode().getCode());
        assertTrue(registry.getOpenSessions().isEmpty());
    }

    @Test
    void anOverlongCloseReasonIsTruncatedToWhatTheProtocolAllows() {
        session.close(new CloseReason(CloseReason.NORMAL.getCode(), "x".repeat(500)));

        int length = nativeSession.closeReason.getReasonPhrase().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(length <= 123, "close reason must fit the 123 byte control frame budget, was " + length);
    }

    @Test
    void anUnknownCloseCodeFallsBackToUnexpectedCondition() {
        session.close(new CloseReason(4999, "custom"));

        assertNotNull(nativeSession.closeReason);
    }

    @Test
    void closingAnAlreadyClosedSessionIsQuiet() {
        nativeSession.open = false;
        session.close(CloseReason.NORMAL);

        assertEquals(null, nativeSession.closeReason);
    }

    @Test
    void sessionStateIsReadFromTheContainerAndTheOriginatingRequest() {
        assertEquals("test-session", session.getId());
        assertEquals("13", session.getProtocolVersion());
        assertFalse(session.isSecure());
        assertTrue(session.isOpen());
        assertEquals("/chat/football/fred", session.getRequestURI().toString());
        assertEquals("football", session.getUriVariables().get("topic", String.class).orElse(null));
        assertTrue(session.getSubprotocol().isEmpty(), "an empty negotiated subprotocol reads as absent");
        assertSame(nativeSession, session.getNativeSession());
        assertTrue(session.toString().contains("test-session"));
    }

    @Test
    void attributesAreReadableAndMutable() {
        session.put("user", "fred");

        assertEquals("fred", session.get("user", String.class).orElse(null));
        assertTrue(session.names().contains("user"));
        assertTrue(session.values().contains("fred"));

        session.remove("user");
        assertTrue(session.get("user", String.class).isEmpty());

        session.put("a", "1");
        session.clear();
        assertTrue(session.names().isEmpty());
    }

    @Test
    void openSessionsComeFromTheRegistry() {
        assertEquals(1, session.getOpenSessions().size());
        nativeSession.open = false;
        assertTrue(session.getOpenSessions().isEmpty());
    }
}
