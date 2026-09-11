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
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.runtime.graceful.GracefulShutdownCapable;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With graceful shutdown enabled, stopping the application lets requests that are already being handled finish
 * instead of cutting them off.
 */
class GracefulShutdownTest {

    @Test
    void stoppingTheApplicationWaitsForAnInFlightRequest() throws Exception {
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "GracefulShutdownTest",
            "micronaut.lifecycle.graceful-shutdown.enabled", "true"
        ));
        try {
            GracefulShutdownCapable capable = assertInstanceOf(GracefulShutdownCapable.class, server);
            assertEquals(0, capable.reportActiveTasks().orElseThrow(), "nothing is in flight before the first request");
            SlowController controller = server.getApplicationContext().getBean(SlowController.class);
            HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve("/graceful/slow")).build();

            CompletableFuture<HttpResponse<String>> response;
            try (HttpClient client = HttpClient.newHttpClient()) {
                response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                assertTrue(controller.started.await(10, TimeUnit.SECONDS), "the request must reach the controller");
                assertEquals(1, capable.reportActiveTasks().orElseThrow(), "the in-flight request is counted");

                long stopStarted = System.nanoTime();
                server.getApplicationContext().stop();
                long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStarted);

                HttpResponse<String> completed = response.get(10, TimeUnit.SECONDS);
                assertEquals(200, completed.statusCode());
                assertEquals("finished", completed.body());
                assertTrue(controller.released, "the controller ran to completion");
                assertTrue(stopMillis >= SlowController.WORK.toMillis() / 2,
                    "stopping must have waited for the request, but took only " + stopMillis + "ms");
            }
        } finally {
            server.close();
        }
    }

    @Requires(property = "spec.name", value = "GracefulShutdownTest")
    @Controller("/graceful")
    @Secured(SecurityRule.IS_ANONYMOUS)
    static class SlowController {
        static final Duration WORK = Duration.ofMillis(1500);
        final CountDownLatch started = new CountDownLatch(1);
        volatile boolean released;

        @Get("/slow")
        String slow() throws InterruptedException {
            started.countDown();
            Thread.sleep(WORK.toMillis());
            released = true;
            return "finished";
        }
    }
}
