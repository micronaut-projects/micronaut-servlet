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
import io.micronaut.http.annotation.Get;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requests must be handled concurrently.
 *
 * <p>{@link com.sun.net.httpserver.HttpServer} runs every handler on its own dispatcher thread unless an executor is
 * set, so one request that waits used to block every other request on the server.</p>
 */
@Property(name = "spec.name", value = "ConcurrentRequestTest")
@MicronautTest
class ConcurrentRequestTest {

    private static final int REQUESTS = 12;
    private static final long DELAY_MILLIS = 250;

    @Inject
    EmbeddedServer embeddedServer;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void requestsAreHandledConcurrently() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(embeddedServer.getURI() + "/concurrent/slow"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

            long start = System.nanoTime();
            List<CompletableFuture<java.net.http.HttpResponse<String>>> responses = new ArrayList<>(REQUESTS);
            for (int i = 0; i < REQUESTS; i++) {
                responses.add(client.sendAsync(request, BodyHandlers.ofString()));
            }
            CompletableFuture.allOf(responses.toArray(new CompletableFuture[0])).join();
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            for (CompletableFuture<java.net.http.HttpResponse<String>> response : responses) {
                assertEquals(200, response.get().statusCode());
                assertEquals("slept", response.get().body());
            }

            // handled serially this would take at least REQUESTS * DELAY_MILLIS; allow a wide margin so the
            // assertion only fails when requests are genuinely queued behind one another
            long serialFloor = REQUESTS * DELAY_MILLIS;
            assertTrue(elapsedMillis < serialFloor / 2,
                () -> "took " + elapsedMillis + "ms for " + REQUESTS + " concurrent requests, which is close to the "
                    + serialFloor + "ms a serial server would need");
        }
    }

    @Secured(SecurityRule.IS_ANONYMOUS)
    @Requires(property = "spec.name", value = "ConcurrentRequestTest")
    @Controller("/concurrent")
    static class ConcurrentController {

        @Get(value = "/slow", produces = MediaType.TEXT_PLAIN)
        String slow() throws InterruptedException {
            Thread.sleep(DELAY_MILLIS);
            return "slept";
        }
    }
}
