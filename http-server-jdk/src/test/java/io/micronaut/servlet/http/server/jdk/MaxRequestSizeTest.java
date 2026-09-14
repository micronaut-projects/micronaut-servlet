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
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * micronaut.server.max-request-size applies to every request body on the built-in server too: the JDK server
 * reads bodies as a stream, so the limit is enforced by the stream rather than by the reactive body.
 */
class MaxRequestSizeTest {

    private static EmbeddedServer server;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "MaxRequestSizeTest",
            "micronaut.server.max-request-size", "1024"
        ));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void aBodyWithinTheLimitIsAccepted() throws Exception {
        HttpResponse<String> response = send("x".repeat(512), true);
        assertEquals(200, response.statusCode());
        assertEquals("512", response.body());
    }

    @Test
    void aBodyDeclaringALengthOverTheLimitIsRefusedBeforeTheRouteRuns() throws Exception {
        SizeController controller = server.getApplicationContext().getBean(SizeController.class);
        controller.reached = false;
        HttpResponse<String> response = send("x".repeat(4096), true);
        assertEquals(413, response.statusCode());
        assertFalse(controller.reached, "the route must not run for a body that is too large");
    }

    @Test
    void aChunkedBodyThatGrowsPastTheLimitIsRefused() throws Exception {
        HttpResponse<String> response = send("x".repeat(4096), false);
        assertEquals(413, response.statusCode());
    }

    private static HttpResponse<String> send(String body, boolean declareLength) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publisher = declareLength
            ? HttpRequest.BodyPublishers.ofString(body)
            : HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(body));
        HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve("/size"))
            .header("Content-Type", MediaType.TEXT_PLAIN)
            .POST(publisher)
            .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Requires(property = "spec.name", value = "MaxRequestSizeTest")
    @Controller("/size")
    @Secured(SecurityRule.IS_ANONYMOUS)
    static class SizeController {
        volatile boolean reached;

        @Post
        @Consumes(MediaType.TEXT_PLAIN)
        String size(@Body String body) {
            reached = true;
            return String.valueOf(body.length());
        }
    }
}
