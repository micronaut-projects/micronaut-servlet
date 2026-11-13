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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpExchangeHttpServletRequestTest {

    @Test
    void theRequestUriAndQueryAreRaw() {
        HttpExchangeHttpServletRequest request = newRequest("/a%2520b/c%3Fd%23e%2Ff?x=%20", new Headers());

        assertEquals("/a%2520b/c%3Fd%23e%2Ff", request.getRequestURI());
        assertEquals("x=%20", request.getQueryString());
        assertEquals("http", request.getScheme());
        assertFalse(request.isSecure());
    }

    @Test
    void theRequestUrlIsBuiltFromTheHostHeaderAndTheRawPath() {
        Headers headers = new Headers();
        headers.add("Host", "example.com:8081");
        HttpExchangeHttpServletRequest request = newRequest("/a%20b?x=1", headers);

        assertTrue(request.hasValidHostHeader());
        assertEquals("example.com", request.getServerName());
        assertEquals(8081, request.getServerPort());
        assertEquals("http://example.com:8081/a%20b", request.getRequestURL().toString());
    }

    @Test
    void aHostHeaderWithoutAPortUsesThePortTheConnectionWasAcceptedOn() {
        Headers headers = new Headers();
        headers.add("Host", "[::1]");
        HttpExchangeHttpServletRequest request = newRequest("/", headers);

        assertTrue(request.hasValidHostHeader());
        assertEquals("::1", request.getServerName());
        assertEquals(8080, request.getServerPort(), "the local port, as the Servlet API and Tomcat have it");
        assertEquals("http://[::1]:8080/", request.getRequestURL().toString());
    }

    @Test
    void aHostNameWithAnUnderscoreIsAccepted() {
        Headers headers = new Headers();
        headers.add("Host", "my_service:9000");
        HttpExchangeHttpServletRequest request = newRequest("/", headers);

        assertTrue(request.hasValidHostHeader());
        assertEquals("my_service", request.getServerName());
        assertEquals(9000, request.getServerPort());
    }

    @Test
    void withoutAHostHeaderTheLocalAddressIsUsedWithoutALookup() {
        HttpExchangeHttpServletRequest request = newRequest("/", new Headers());

        assertTrue(request.hasValidHostHeader());
        assertEquals("127.0.0.1", request.getServerName());
        assertEquals(8080, request.getServerPort());
        assertEquals("127.0.0.1", request.getRemoteHost(), "no reverse lookup of the client address");
    }

    @Test
    void aMalformedHostHeaderIsNotReflected() {
        for (String host : List.of("evil.example/phish?x", "a b", "example.com:8080:1", "example.com:99999", "user@example.com", "[::1", "[::::]", "[1.2.3.4]")) {
            Headers headers = new Headers();
            headers.add("Host", host);
            HttpExchangeHttpServletRequest request = newRequest("/", headers);

            assertFalse(request.hasValidHostHeader(), host);
            assertEquals("127.0.0.1", request.getServerName(), host);
            assertEquals(8080, request.getServerPort(), host);
        }
    }

    @Test
    void repeatedHostHeadersAreRejected() {
        Headers headers = new Headers();
        headers.add("Host", "a.example");
        headers.add("Host", "b.example");
        HttpExchangeHttpServletRequest request = newRequest("/", headers);

        assertFalse(request.hasValidHostHeader());
        assertEquals("127.0.0.1", request.getServerName());
    }

    @Test
    void cookiesFromEveryCookieHeaderAreDecoded() {
        Headers headers = new Headers();
        headers.add("Cookie", "a=1");
        headers.add("Cookie", "b=2; c=3");
        HttpExchangeHttpServletRequest request = newRequest("/", headers);

        Cookie[] cookies = request.getCookies();
        assertEquals(3, cookies.length);
        assertEquals("a", cookies[0].getName());
        assertEquals("c", cookies[2].getName());
    }

    private static HttpExchangeHttpServletRequest newRequest(String uri, Headers headers) {
        return new HttpExchangeHttpServletRequest(new FakeExchange(uri, headers), (body, charset) -> Collections.emptyMap());
    }

    /**
     * Minimal {@link HttpExchange} carrying a fixed GET request.
     */
    private static final class FakeExchange extends HttpExchange {
        private final URI uri;
        private final Headers requestHeaders;
        private final Headers responseHeaders = new Headers();

        private FakeExchange(String uri, Headers requestHeaders) {
            this.uri = URI.create(uri);
            this.requestHeaders = requestHeaders;
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
            return "GET";
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
