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

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.CookieValue;
import io.micronaut.http.annotation.Get;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Percent-encoded request targets, sent over a raw socket so that no client normalises them first. The path used to
 * be decoded by the JDK server and then a second time by the engine.
 */
@Property(name = "spec.name", value = "RequestUriDecodingTest")
// so that an unmatched route is the router's 404, not the 401 that security answers for it
@Property(name = "micronaut.security.enabled", value = "false")
@MicronautTest
class RequestUriDecodingTest {

    @Inject
    EmbeddedServer embeddedServer;

    @Test
    void anEncodedSpaceIsDecodedOnce() throws IOException {
        assertResponse("/probe/echo/hello%20world", 200, "echo=hello world");
    }

    @Test
    void anEncodedPercentIsNotDecodedTwice() throws IOException {
        assertResponse("/probe/echo/a%2520b", 200, "echo=a%20b");
    }

    @Test
    void anEncodedHashStaysInThePath() throws IOException {
        assertResponse("/probe/echo/x%23frag", 200, "echo=x#frag");
    }

    @Test
    void anEncodedSlashDoesNotSplitThePath() throws IOException {
        assertResponse("/probe/echo/a%2Fb", 200, "echo=a/b");
    }

    @Test
    void anEncodedQuestionMarkDoesNotStartTheQuery() throws IOException {
        String response = rawRequest("GET /probe/path%3Fx=1 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 404"), response);
        assertFalse(response.endsWith("path"), response);
    }

    @Test
    void aMalformedHostHeaderIsABadRequest() throws IOException {
        String response = rawRequest("GET /probe/path HTTP/1.1\r\nHost: evil.example/phish?x\r\nConnection: close\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 400"), response);
        assertFalse(response.contains("evil.example"), response);
    }

    @Test
    void repeatedHostHeadersAreABadRequest() throws IOException {
        String response = rawRequest("GET /probe/path HTTP/1.1\r\nHost: a.example\r\nHost: b.example\r\nConnection: close\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 400"), response);
    }

    @Test
    void aHostHeaderWithoutAPortKeepsTheServerPort() throws IOException {
        String response = rawRequest("GET /probe/url HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n");

        assertTrue(response.contains("http://example.com:" + embeddedServer.getPort() + "/probe/url"), response);
    }

    @Test
    void aMalformedQueryStringIsABadRequest() throws IOException {
        String response = rawRequest("GET /probe/path?a=%ZZ HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 400"), response);
        // newer JDKs reject the target in the JDK server itself, with a one-line message naming the exception;
        // either way no stack trace and nothing of the application's may reach the client
        assertFalse(response.contains("\tat "), response);
        assertFalse(response.contains("io.micronaut"), response);
        assertFalse(response.contains("InvalidRequestUriException"), response);
    }

    @Test
    void cookiesFromEveryCookieHeaderAreRead() throws IOException {
        String response = rawRequest("GET /probe/cookies HTTP/1.1\r\nHost: localhost\r\nCookie: a=1\r\nCookie: b=2\r\nConnection: close\r\n\r\n");

        assertTrue(response.endsWith("a=1,b=2"), response);
    }

    private void assertResponse(String target, int status, String body) throws IOException {
        String response = rawRequest("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 " + status), response);
        assertTrue(response.endsWith(body), response);
    }

    private String rawRequest(String request) throws IOException {
        try (Socket socket = new Socket(embeddedServer.getHost(), embeddedServer.getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Secured(SecurityRule.IS_ANONYMOUS)
    @Requires(property = "spec.name", value = "RequestUriDecodingTest")
    @Controller("/probe")
    static class ProbeController {

        @Get(value = "/echo/{name}", produces = MediaType.TEXT_PLAIN)
        String echo(String name) {
            return "echo=" + name;
        }

        @Get(value = "/path", produces = MediaType.TEXT_PLAIN)
        String path() {
            return "path";
        }

        @Get(value = "/url", produces = MediaType.TEXT_PLAIN)
        String url(HttpServletRequest request) {
            return request.getRequestURL().toString();
        }

        @Get(value = "/cookies", produces = MediaType.TEXT_PLAIN)
        String cookies(@CookieValue("a") String a, @CookieValue("b") String b) {
            return "a=" + a + ",b=" + b;
        }
    }
}
