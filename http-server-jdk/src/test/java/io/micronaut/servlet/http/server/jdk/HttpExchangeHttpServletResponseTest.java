/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.http.server.jdk;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpExchangeHttpServletResponseTest {

    @Test
    void headerNamesAreCaseInsensitive() {
        HttpExchangeHttpServletResponse response = newResponse("GET");
        response.setContentType("text/plain");

        assertTrue(response.containsHeader("content-type"));
        assertEquals("text/plain", response.getHeader("CONTENT-TYPE"));

        response.setHeader("Content-Type", "application/json");
        assertEquals(1, response.getHeaders("content-type").size(), "the same header must not be stored twice");
        assertEquals("application/json", response.getContentType());
    }

    @Test
    void addHeaderAppendsWhileSetHeaderReplaces() {
        HttpExchangeHttpServletResponse response = newResponse("GET");
        response.addHeader("Vary", "Origin");
        response.addHeader("vary", "Accept");

        assertEquals(List.of("Origin", "Accept"), List.copyOf(response.getHeaders("Vary")));

        response.setHeader("Vary", "Accept-Encoding");
        assertEquals(List.of("Accept-Encoding"), List.copyOf(response.getHeaders("Vary")));
    }

    @Test
    void setHeaderWithNullRemovesTheHeader() {
        HttpExchangeHttpServletResponse response = newResponse("GET");
        response.setHeader("X-Trace", "abc");
        response.setHeader("X-Trace", null);

        assertFalse(response.containsHeader("X-Trace"));
        assertNull(response.getHeader("X-Trace"));
    }

    @Test
    void cookiesAreEncodedWithTheirAttributes() {
        HttpExchangeHttpServletResponse response = newResponse("GET");
        Cookie first = new Cookie("first", "one");
        first.setHttpOnly(true);
        first.setPath("/");
        first.setMaxAge(60);
        first.setAttribute("SameSite", "Strict");
        response.addCookie(first);
        response.addCookie(new Cookie("second", "two"));

        Collection<String> setCookie = response.getHeaders("Set-Cookie");
        assertEquals(2, setCookie.size());
        String encoded = setCookie.iterator().next().toLowerCase(Locale.ROOT);
        assertTrue(encoded.startsWith("first=one"), () -> "unexpected cookie: " + encoded);
        assertTrue(encoded.contains("httponly"), () -> "attributes lost: " + encoded);
        assertTrue(encoded.contains("samesite=strict"), () -> "SameSite lost: " + encoded);
    }

    @Test
    void attributesTheCookieApiCannotCarryAreStillWritten() {
        HttpExchangeHttpServletResponse response = newResponse("GET");
        Cookie cookie = new Cookie("session", "abc");
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Lax");
        cookie.setAttribute("Partitioned", "");
        cookie.setAttribute("X-Vendor", "something");
        response.addCookie(cookie);

        String encoded = response.getHeader("Set-Cookie");
        assertTrue(encoded.contains("Partitioned"), () -> "valueless attribute dropped: " + encoded);
        assertTrue(encoded.contains("X-Vendor=something"), () -> "custom attribute dropped: " + encoded);
        assertEquals(1, countOccurrences(encoded.toLowerCase(Locale.ROOT), "samesite"),
            () -> "SameSite must not be written twice: " + encoded);
        assertEquals(1, countOccurrences(encoded.toLowerCase(Locale.ROOT), "path="),
            () -> "Path must not be written twice: " + encoded);
    }

    @Test
    void anExplicitExpiresIsKeptWhenNoMaxAgeDerivesIt() {
        HttpExchangeHttpServletResponse response = newResponse("GET");
        Cookie cookie = new Cookie("session", "abc");
        cookie.setAttribute("Expires", "Wed, 21 Oct 2026 07:28:00 GMT");
        cookie.setAttribute("Comment", "legacy");
        response.addCookie(cookie);

        String encoded = response.getHeader("Set-Cookie");
        assertTrue(encoded.contains("Expires=Wed, 21 Oct 2026 07:28:00 GMT"), () -> "explicit Expires dropped: " + encoded);
        assertTrue(encoded.contains("Comment=legacy"), () -> "Comment dropped: " + encoded);
    }

    @Test
    void anExplicitExpiresYieldsToTheOneDerivedFromMaxAge() {
        HttpExchangeHttpServletResponse response = newResponse("GET");
        Cookie cookie = new Cookie("session", "abc");
        cookie.setMaxAge(60);
        cookie.setAttribute("Expires", "Wed, 21 Oct 2026 07:28:00 GMT");
        response.addCookie(cookie);

        String encoded = response.getHeader("Set-Cookie");
        assertEquals(1, countOccurrences(encoded.toLowerCase(Locale.ROOT), "expires="),
            () -> "Expires must not be written twice: " + encoded);
    }

    @Test
    void flushingCommitsTheResponseAndKeepsTheBodyChannelOpen() throws IOException {
        FakeExchange exchange = new FakeExchange("GET");
        HttpExchangeHttpServletResponse response = new HttpExchangeHttpServletResponse(exchange, r -> { });

        response.flushBuffer();

        assertTrue(response.isCommitted(), "a flush commits the response");
        assertTrue(response.isOutputStreamRequested(), "a flushed response must be framed as able to carry a body");
        response.getOutputStream().write("late".getBytes());
        assertEquals("late", exchange.responseBody.toString(), "a write after an explicit flush still reaches the wire");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = haystack.indexOf(needle);
        while (from >= 0) {
            count++;
            from = haystack.indexOf(needle, from + needle.length());
        }
        return count;
    }

    @Test
    void intAndDateHeadersAreWritten() {
        HttpExchangeHttpServletResponse response = newResponse("GET");
        response.setIntHeader("X-Count", 1);
        response.addIntHeader("X-Count", 2);
        assertEquals(List.of("1", "2"), List.copyOf(response.getHeaders("X-Count")));

        response.setDateHeader("Last-Modified", 0L);
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", response.getHeader("Last-Modified"));
        response.addDateHeader("Last-Modified", 0L);
        assertEquals(2, response.getHeaders("Last-Modified").size());
    }

    @Test
    void contentLengthIsReadBackFromTheHeader() {
        HttpExchangeHttpServletResponse response = newResponse("GET");
        assertEquals(-1L, response.getDeclaredContentLength(), "absent means unknown");

        response.setContentLength(12);
        assertEquals(12L, response.getDeclaredContentLength());

        response.setContentLengthLong(9001L);
        assertEquals(9001L, response.getDeclaredContentLength());

        response.setHeader("Content-Length", "not a number");
        assertEquals(-1L, response.getDeclaredContentLength(), "an unparseable length is unknown, not a failure");
    }

    @Test
    void statusesThatCannotCarryABodyAreRecognised() {
        assertFalse(newResponseWithStatus("GET", 100).isBodyAllowed());
        assertFalse(newResponseWithStatus("GET", 204).isBodyAllowed());
        assertFalse(newResponseWithStatus("GET", 205).isBodyAllowed());
        assertFalse(newResponseWithStatus("GET", 304).isBodyAllowed());
        assertTrue(newResponseWithStatus("GET", 200).isBodyAllowed());
        assertTrue(newResponseWithStatus("GET", 500).isBodyAllowed());
        assertFalse(newResponseWithStatus("HEAD", 200).isBodyAllowed(), "a response to HEAD never carries a body");
    }

    @Test
    void headersAreSentAtMostOnce() throws IOException {
        FakeExchange exchange = new FakeExchange("GET");
        int[] commits = {0};
        HttpExchangeHttpServletResponse response = new HttpExchangeHttpServletResponse(exchange, r -> commits[0]++);

        assertFalse(response.isOutputStreamRequested());
        response.commitHeaders();
        response.commitHeaders();
        response.getOutputStream();

        assertEquals(1, commits[0], "a second commit must not re-send the status line");
        assertTrue(response.isOutputStreamRequested());
    }

    @Test
    void writesAreDiscardedWhenTheResponseCannotCarryABody() throws IOException {
        FakeExchange exchange = new FakeExchange("GET");
        HttpExchangeHttpServletResponse response = new HttpExchangeHttpServletResponse(exchange, r -> { });
        response.setStatus(304);

        response.getOutputStream().write("body".getBytes());
        response.getOutputStream().flush();

        assertEquals(0, exchange.responseBody.size(), "a 304 must not put bytes on the wire");
    }

    @Test
    void bodyIsWrittenWhenTheStatusAllowsIt() throws IOException {
        FakeExchange exchange = new FakeExchange("GET");
        HttpExchangeHttpServletResponse response = new HttpExchangeHttpServletResponse(exchange, r -> { });

        response.getOutputStream().write("hello".getBytes());

        assertEquals("hello", exchange.responseBody.toString());
    }

    @Test
    void resetClearsStatusAndHeadersUntilTheResponseIsCommitted() {
        HttpExchangeHttpServletResponse response = newResponse("GET");
        response.setStatus(404);
        response.setHeader("X-Trace", "abc");

        response.reset();

        assertEquals(200, response.getStatus());
        assertFalse(response.containsHeader("X-Trace"));

        response.setCommitted(true);
        assertTrue(response.isCommitted());
        assertThrows(IllegalStateException.class, response::reset);
    }

    @Test
    void sendErrorWritesTheStatusAndBody() throws IOException {
        FakeExchange exchange = new FakeExchange("GET");
        HttpExchangeHttpServletResponse response = new HttpExchangeHttpServletResponse(exchange, r -> { });

        response.sendError(503, "nope");

        assertEquals(503, response.getStatus());
        assertEquals("nope", exchange.responseBody.toString());
        assertEquals(4L, response.getDeclaredContentLength());
    }

    @Test
    void sendRedirectSetsStatusAndLocation() throws IOException {
        HttpExchangeHttpServletResponse response = newResponse("GET");

        response.sendRedirect("/elsewhere", 302, false);

        assertEquals(302, response.getStatus());
        assertEquals("/elsewhere", response.getHeader("Location"));
    }

    @Test
    void characterEncodingComesFromTheContentType() {
        HttpExchangeHttpServletResponse response = newResponse("GET");
        response.setContentType("text/plain;charset=ISO-8859-1");

        assertEquals("ISO-8859-1", response.getCharacterEncoding());
    }

    private static HttpExchangeHttpServletResponse newResponse(String method) {
        return new HttpExchangeHttpServletResponse(new FakeExchange(method), r -> { });
    }

    private static HttpExchangeHttpServletResponse newResponseWithStatus(String method, int status) {
        HttpExchangeHttpServletResponse response = newResponse(method);
        response.setStatus(status);
        return response;
    }

    /**
     * Minimal {@link HttpExchange} that records what the response wrote.
     */
    private static final class FakeExchange extends HttpExchange {
        private final String method;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final List<long[]> sent = new ArrayList<>();

        private FakeExchange(String method) {
            this.method = method;
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return URI.create("/");
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
            // nothing to release
        }

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            sent.add(new long[]{rCode, responseLength});
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress(0);
        }

        @Override
        public int getResponseCode() {
            return sent.isEmpty() ? -1 : (int) sent.get(0)[0];
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress(0);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
            // attributes are not used by the response
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
            // streams are fixed for this fake
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
