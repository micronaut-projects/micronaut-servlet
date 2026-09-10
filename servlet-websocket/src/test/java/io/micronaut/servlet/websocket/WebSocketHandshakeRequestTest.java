package io.micronaut.servlet.websocket;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpRequestWrapper;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.cookie.Cookie;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketHandshakeRequestTest {

    @Test
    void everythingAHandlerCanBindFromIsCopied() {
        MutableHttpRequest<?> source = HttpRequest.GET("/chat/football/fred?dinner=chicken")
            .header("X-Guest", "ann")
            .header("Sec-WebSocket-Key", "abc")
            .cookie(Cookie.of("SESSION", "s1"));
        source.setAttribute("micronaut.SESSION", "session-value");

        WebSocketHandshakeRequest copy = WebSocketHandshakeRequest.snapshot(source);

        assertEquals("/chat/football/fred", copy.getPath());
        assertEquals("ann", copy.getHeaders().get("X-Guest"));
        assertEquals("abc", copy.getHeaders().get("Sec-WebSocket-Key"));
        assertEquals(source.getParameters().get("dinner"), copy.getParameters().get("dinner"));
        assertTrue(copy.getUri().toString().contains("dinner=chicken"));
        assertEquals("s1", copy.getCookies().get("SESSION").getValue());
        assertEquals("session-value", copy.getAttribute("micronaut.SESSION", String.class).orElse(null));
    }

    @Test
    void multipleHeaderValuesSurviveTheCopy() {
        MutableHttpRequest<?> source = HttpRequest.GET("/chat")
            .header("Sec-WebSocket-Protocol", "chat")
            .header("Sec-WebSocket-Protocol", "superchat");

        WebSocketHandshakeRequest copy = WebSocketHandshakeRequest.snapshot(source);

        assertEquals(2, copy.getHeaders().getAll("Sec-WebSocket-Protocol").size());
    }

    @Test
    void theCopyKeepsWorkingAfterTheSourceStopsBeingUsable() {
        MutableHttpRequest<?> source = HttpRequest.GET("/chat").header("X-Guest", "ann");
        WebSocketHandshakeRequest copy = WebSocketHandshakeRequest.snapshot(source);

        // Stands in for a servlet request that the container has recycled.
        HttpRequest<?> recycled = new RecycledRequest(source);

        assertEquals("ann", copy.getHeaders().get("X-Guest"));
        assertThrowsOnAccess(recycled);
    }

    @Test
    void addressesAndTheSecureFlagFallBackWhenTheSourceCannotAnswer() {
        WebSocketHandshakeRequest copy =
            WebSocketHandshakeRequest.snapshot(new AddressLessRequest(HttpRequest.GET("/chat").header("X-Guest", "ann")));

        assertEquals("ann", copy.getHeaders().get("X-Guest"), "the rest of the request still copies");
        assertNotNull(copy.getRemoteAddress());
        assertNotNull(copy.getServerAddress());
        assertFalse(copy.isSecure());
    }

    @Test
    void aSecureSourceProducesASecureCopy() {
        WebSocketHandshakeRequest copy = WebSocketHandshakeRequest.snapshot(HttpRequest.GET("https://example.com/chat"));

        assertTrue(copy.isSecure());
        assertEquals(new InetSocketAddress("example.com", 443).getHostString(), copy.getServerAddress().getHostString());
    }

    private static void assertThrowsOnAccess(HttpRequest<?> recycled) {
        try {
            recycled.getHeaders().get("X-Guest");
            throw new AssertionError("expected the recycled request to fail");
        } catch (IllegalStateException expected) {
            // the point of the snapshot
        }
    }

    /**
     * Stands in for a container that cannot report connection details, so that the
     * fallbacks are exercised.
     */
    private static final class AddressLessRequest extends HttpRequestWrapper<Object> {

        @SuppressWarnings("unchecked")
        AddressLessRequest(HttpRequest<?> delegate) {
            super((HttpRequest<Object>) delegate);
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            throw new UnsupportedOperationException("no address available");
        }

        @Override
        public InetSocketAddress getServerAddress() {
            throw new UnsupportedOperationException("no address available");
        }

        @Override
        public boolean isSecure() {
            throw new UnsupportedOperationException("not known");
        }
    }

    /**
     * Stands in for a servlet request after the container has recycled it: the accessors a
     * handler would reach for throw.
     */
    private static final class RecycledRequest extends HttpRequestWrapper<Object> {

        @SuppressWarnings("unchecked")
        RecycledRequest(HttpRequest<?> delegate) {
            super((HttpRequest<Object>) delegate);
        }

        @Override
        public io.micronaut.http.HttpHeaders getHeaders() {
            throw new IllegalStateException("request recycled");
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            throw new IllegalStateException("request recycled");
        }

        @Override
        public InetSocketAddress getServerAddress() {
            throw new IllegalStateException("request recycled");
        }

        @Override
        public boolean isSecure() {
            throw new IllegalStateException("request recycled");
        }
    }
}
