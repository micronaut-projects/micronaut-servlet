/*
 * Copyright 2017-2025 original authors
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
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Status;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A body-less response must terminate the exchange instead of announcing a chunked body that never arrives.
 *
 * @see <a href="https://github.com/micronaut-projects/micronaut-servlet/issues/1117">#1117</a>
 */
@Property(name = "spec.name", value = "BodyLessResponseTest")
@MicronautTest
class BodyLessResponseTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Inject
    EmbeddedServer embeddedServer;

    @Test
    void noContentDoesNotHang() throws IOException, InterruptedException {
        java.net.http.HttpResponse<String> response = exchange("/body-less/no-content", "GET");
        assertEquals(HttpStatus.NO_CONTENT.getCode(), response.statusCode());
        assertEquals("", response.body());
        assertTrue(response.headers().firstValue("Transfer-encoding").isEmpty(),
            "a 204 must not be announced as chunked");
    }

    @Test
    void notModifiedDoesNotHang() throws IOException, InterruptedException {
        java.net.http.HttpResponse<String> response = exchange("/body-less/not-modified", "GET");
        assertEquals(HttpStatus.NOT_MODIFIED.getCode(), response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void emptyOkDoesNotHang() throws IOException, InterruptedException {
        java.net.http.HttpResponse<String> response = exchange("/body-less/empty", "GET");
        assertEquals(HttpStatus.OK.getCode(), response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void headHasNoBody() throws IOException, InterruptedException {
        java.net.http.HttpResponse<String> response = exchange("/body-less/text", "HEAD");
        assertEquals(HttpStatus.OK.getCode(), response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void getStillReturnsABody() throws IOException, InterruptedException {
        java.net.http.HttpResponse<String> response = exchange("/body-less/text", "GET");
        assertEquals(HttpStatus.OK.getCode(), response.statusCode());
        assertEquals("Hello World", response.body());
    }

    @Test
    void multiValuedHeadersAreNotCollapsed() throws IOException, InterruptedException {
        java.net.http.HttpResponse<String> response = exchange("/body-less/cookies", "GET");
        assertEquals(HttpStatus.OK.getCode(), response.statusCode());
        List<String> setCookie = response.headers().allValues("Set-Cookie");
        assertEquals(2, setCookie.size(), "both cookies must survive; addHeader used to replace the previous value");
        assertTrue(setCookie.stream().anyMatch(v -> v.startsWith("first=one")), () -> "missing first cookie in " + setCookie);
        assertTrue(setCookie.stream().anyMatch(v -> v.startsWith("second=two")), () -> "missing second cookie in " + setCookie);
        assertTrue(setCookie.stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("httponly")), () -> "cookie attributes lost in " + setCookie);
    }

    @Test
    void headerLookupIsCaseInsensitive() throws IOException, InterruptedException {
        java.net.http.HttpResponse<String> response = exchange("/body-less/duplicate-content-type", "GET");
        assertEquals(HttpStatus.OK.getCode(), response.statusCode());
        assertEquals(1, response.headers().allValues("content-type").size(),
            "setContentType and setHeader(\"Content-Type\") must address the same header");
        assertFalse(response.body().isEmpty());
    }

    private java.net.http.HttpResponse<String> exchange(String path, String method) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(embeddedServer.getURI() + path))
                .timeout(TIMEOUT)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
            return client.send(request, BodyHandlers.ofString());
        }
    }

    @Secured(SecurityRule.IS_ANONYMOUS)
    @Requires(property = "spec.name", value = "BodyLessResponseTest")
    @Controller("/body-less")
    static class BodyLessController {

        @Get("/no-content")
        @Status(HttpStatus.NO_CONTENT)
        void noContent() {
        }

        @Get("/not-modified")
        HttpResponse<?> notModified() {
            return HttpResponse.notModified();
        }

        @Get("/empty")
        HttpResponse<?> empty() {
            return HttpResponse.ok();
        }

        @Get(value = "/text", produces = MediaType.TEXT_PLAIN)
        String text() {
            return "Hello World";
        }

        @Get(value = "/cookies", produces = MediaType.TEXT_PLAIN)
        HttpResponse<String> cookies() {
            return HttpResponse.ok("cookies")
                .cookie(io.micronaut.http.cookie.Cookie.of("first", "one").httpOnly(true).path("/"))
                .cookie(io.micronaut.http.cookie.Cookie.of("second", "two").path("/"));
        }

        @Get(value = "/duplicate-content-type", produces = MediaType.TEXT_PLAIN)
        HttpResponse<String> duplicateContentType(@Header(defaultValue = "none", value = "X-Unused") String unused) {
            return HttpResponse.ok("body").header("content-type", MediaType.TEXT_PLAIN);
        }
    }
}
