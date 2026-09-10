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
import io.micronaut.http.form.FormUrlEncodedDecoder;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpExchangeHttpServletRequestTest {

    @Test
    void anAbsentContentLengthIsUnknownRatherThanEmpty() {
        HttpExchangeHttpServletRequest request = newRequest("GET", "/", new Headers(), "");

        assertEquals(-1L, request.getContentLengthLong(), "absent means unknown, not zero");
        assertEquals(-1, request.getContentLength());
    }

    @Test
    void aDeclaredContentLengthIsReported() {
        Headers headers = new Headers();
        headers.add("Content-Length", "5");
        HttpExchangeHttpServletRequest request = newRequest("POST", "/", headers, "hello");

        assertEquals(5L, request.getContentLengthLong());
        assertEquals(5, request.getContentLength());
    }

    @Test
    void anUnparseableContentLengthIsUnknown() {
        Headers headers = new Headers();
        headers.add("Content-Length", "banana");
        HttpExchangeHttpServletRequest request = newRequest("POST", "/", headers, "");

        assertEquals(-1L, request.getContentLengthLong());
    }

    @Test
    void theSchemeComesFromTheExchangeNotTheRequestUri() {
        // the JDK server hands over a path-only URI, so a scheme read from it would always be null
        HttpExchangeHttpServletRequest request = newRequest("GET", "/some/path", new Headers(), "");

        assertEquals("http", request.getScheme());
        assertFalse(request.isSecure());
    }

    @Test
    void theBodyIsReadInBulkAndOnlyOnce() throws IOException {
        Headers headers = new Headers();
        headers.add("Content-Length", "11");
        HttpExchangeHttpServletRequest request = newRequest("POST", "/", headers, "hello world");

        ServletInputStream inputStream = request.getInputStream();
        assertFalse(inputStream.isFinished());
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), inputStream.readAllBytes());
        assertTrue(inputStream.isFinished(), "the stream reports itself finished once drained");
    }

    @Test
    void theReaderUsesTheContentTypeCharset() throws IOException {
        Headers headers = new Headers();
        headers.add("Content-Type", "text/plain;charset=ISO-8859-1");
        // Accept-Charset describes the response, so it must not decide how the body is decoded
        headers.add("Accept-Charset", "UTF-16");
        byte[] body = "café".getBytes(Charset.forName("ISO-8859-1"));
        HttpExchangeHttpServletRequest request = newRequest("POST", "/", headers, body);

        assertEquals("ISO-8859-1", request.getCharacterEncoding());
        assertEquals("café", request.getReader().readLine());
    }

    @Test
    void theReaderDefaultsToUtf8WhenTheContentTypeSaysNothing() throws IOException {
        Headers headers = new Headers();
        headers.add("Accept-Charset", "ISO-8859-1");
        HttpExchangeHttpServletRequest request = newRequest("POST", "/", headers, "café");

        assertEquals("UTF-8", request.getCharacterEncoding());
        assertEquals("café", request.getReader().readLine());
    }

    @Test
    void queryParametersAreDecoded() {
        HttpExchangeHttpServletRequest request = newRequest("GET", "/search?q=micronaut&tag=a&tag=b", new Headers(), "");

        assertEquals("micronaut", request.getParameter("q"));
        assertArrayEquals(new String[]{"a", "b"}, request.getParameterValues("tag"));
    }

    @Test
    void formBodiesAreDecodedIntoParameters() {
        Headers headers = new Headers();
        headers.add("Content-Type", "application/x-www-form-urlencoded");
        HttpExchangeHttpServletRequest request = newRequest("POST", "/submit?from=query", headers, "name=value&other=thing");

        assertEquals("value", request.getParameter("name"));
        assertEquals("thing", request.getParameter("other"));
        assertEquals("query", request.getParameter("from"), "query parameters survive alongside the form");
    }

    @Test
    void aMultipartBodyIsNotDecodedAsAForm() throws IOException {
        Headers headers = new Headers();
        headers.add("Content-Type", "multipart/form-data; boundary=xyz");
        String body = "--xyz\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nb\r\n--xyz--\r\n";
        HttpExchangeHttpServletRequest request = newRequest("POST", "/upload", headers, body);

        // decoding this as a form both mis-parses it and consumes the body before anything can read it properly
        assertTrue(request.getParameterMap().isEmpty());
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), request.getInputStream().readAllBytes());
    }

    @Test
    void cookiesAreDecodedFromTheHeader() {
        Headers headers = new Headers();
        headers.add("Cookie", "first=one; second=two");
        HttpExchangeHttpServletRequest request = newRequest("GET", "/", headers, "");

        Cookie[] cookies = request.getCookies();
        assertEquals(2, cookies.length);
        assertEquals("first", cookies[0].getName());
        assertEquals("one", cookies[0].getValue());
        assertEquals("second", cookies[1].getName());
    }

    @Test
    void noCookieHeaderYieldsNoCookies() {
        assertEquals(0, newRequest("GET", "/", new Headers(), "").getCookies().length);
    }

    @Test
    void headersAreExposedByNameAndValue() {
        Headers headers = new Headers();
        headers.add("X-Trace", "abc");
        headers.add("X-Count", "7");
        HttpExchangeHttpServletRequest request = newRequest("GET", "/", headers, "");

        assertEquals("abc", request.getHeader("X-Trace"));
        assertEquals(7, request.getIntHeader("X-Count"));
        assertEquals(-1, request.getIntHeader("X-Absent"));
        assertTrue(List.copyOf(java.util.Collections.list(request.getHeaderNames())).stream()
            .anyMatch(name -> name.equalsIgnoreCase("X-Trace")));
    }

    @Test
    void attributesRoundTrip() {
        HttpExchangeHttpServletRequest request = newRequest("GET", "/", new Headers(), "");
        request.setAttribute("key", "value");

        assertEquals("value", request.getAttribute("key"));

        request.removeAttribute("key");
        assertEquals(null, request.getAttribute("key"));
    }

    @Test
    void theRequestLineIsExposed() {
        HttpExchangeHttpServletRequest request = newRequest("DELETE", "/things/1?force=true", new Headers(), "");

        assertEquals("DELETE", request.getMethod());
        assertEquals("/things/1", request.getRequestURI());
        assertEquals("force=true", request.getQueryString());
        assertEquals("HTTP/1.1", request.getProtocol());
        assertFalse(request.isAsyncSupported(), "the JDK server has no asynchronous dispatch");
    }

    private static HttpExchangeHttpServletRequest newRequest(String method, String uri, Headers headers, String body) {
        return newRequest(method, uri, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpExchangeHttpServletRequest newRequest(String method, String uri, Headers headers, byte[] body) {
        return new HttpExchangeHttpServletRequest(
            new FakeExchange(method, uri, headers, body),
            new TestFormUrlEncodedDecoder()
        );
    }

    /**
     * Decodes {@code a=b&c=d} without pulling in the server's own decoder bean.
     */
    private static final class TestFormUrlEncodedDecoder implements FormUrlEncodedDecoder {
        @Override
        public Map<String, Object> decode(String formUrlEncodedString, Charset charset) {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            for (String pair : formUrlEncodedString.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    result.put(pair.substring(0, equals), pair.substring(equals + 1));
                }
            }
            return result;
        }
    }

    /**
     * Minimal {@link HttpExchange} carrying a fixed request.
     */
    private static final class FakeExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders;
        private final InputStream requestBody;
        private final Headers responseHeaders = new Headers();

        private FakeExchange(String method, String uri, Headers requestHeaders, byte[] body) {
            this.method = method;
            this.uri = URI.create(uri);
            this.requestHeaders = requestHeaders;
            this.requestBody = new ByteArrayInputStream(body);
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
            return uri;
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
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return new ByteArrayOutputStream();
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            // responses are not the subject of this test
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public int getResponseCode() {
            return -1;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
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
            // exchange attributes are not used by the request
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
