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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.sse.Event;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-sent events reach the client as they are produced on the built-in server, not once the stream ends.
 */
class ServerSentEventsTest {

    @Test
    void eventsArriveAsTheyAreProduced() throws Exception {
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "ServerSentEventsTest"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            SseController controller = server.getApplicationContext().getBean(SseController.class);
            HttpResponse<java.io.InputStream> response = client.send(
                HttpRequest.newBuilder(server.getURI().resolve("/sse/events")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith(MediaType.TEXT_EVENT_STREAM));

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        lines.add(line);
                        if (lines.size() == 1) {
                            // the first event has been read while the producer is still held back
                            controller.release.countDown();
                        }
                    }
                }
            }
            assertEquals(List.of("data: one", "data: two", "data: three"), lines);
        } finally {
            server.close();
        }
    }

    @Requires(property = "spec.name", value = "ServerSentEventsTest")
    @Controller("/sse")
    @Secured(SecurityRule.IS_ANONYMOUS)
    static class SseController {
        final CountDownLatch release = new CountDownLatch(1);

        @Get("/events")
        @Produces(MediaType.TEXT_EVENT_STREAM)
        Publisher<Event<String>> events() {
            // the second event is only produced once the client has seen the first, which proves the first was
            // flushed rather than buffered until the stream ended
            return Flux.concat(
                Flux.just(Event.of("one")),
                Flux.defer(() -> {
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            return Flux.error(new IllegalStateException("the first event was never seen by the client"));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Flux.error(e);
                    }
                    return Flux.just(Event.of("two"), Event.of("three"));
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            );
        }
    }
}
