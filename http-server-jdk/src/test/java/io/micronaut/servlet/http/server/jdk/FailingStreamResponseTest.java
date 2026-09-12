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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A response whose body fails partway must not reach the client looking complete.
 *
 * <p>The headers are already on the wire by the time the body source fails, so the status cannot be corrected. The
 * exchange must therefore end without a well-formed terminator, letting the client see a truncated transfer rather
 * than a successful one.</p>
 */
@Property(name = "spec.name", value = "FailingStreamResponseTest")
@MicronautTest
class FailingStreamResponseTest {

    private static final String FIRST_CHUNK = "the first chunk arrives, then the source fails";

    @Inject
    EmbeddedServer embeddedServer;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aBodyThatFailsPartwayDoesNotLookComplete() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(embeddedServer.getURI() + "/failing-stream/partial"))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

            // the client must not be handed a complete-looking response; reading the body has to fail
            assertThrows(IOException.class, () -> client.send(request, BodyHandlers.ofString()),
                "a truncated response must surface as a failed transfer, not a successful one");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aBodyThatSucceedsIsStillDelivered() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(embeddedServer.getURI() + "/failing-stream/whole"))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

            java.net.http.HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(FIRST_CHUNK, response.body());
        }
    }

    @Secured(SecurityRule.IS_ANONYMOUS)
    @Requires(property = "spec.name", value = "FailingStreamResponseTest")
    @Controller("/failing-stream")
    static class FailingStreamController {

        @Get(value = "/partial", produces = MediaType.TEXT_PLAIN)
        InputStream partial() {
            return new FailingInputStream();
        }

        @Get(value = "/whole", produces = MediaType.TEXT_PLAIN)
        InputStream whole() {
            return new java.io.ByteArrayInputStream(FIRST_CHUNK.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Yields one chunk and then fails, standing in for a body source that dies midway.
     */
    private static final class FailingInputStream extends InputStream {
        private final byte[] first = FIRST_CHUNK.getBytes(StandardCharsets.UTF_8);
        private int position;

        @Override
        public int read() throws IOException {
            if (position < first.length) {
                return first[position++] & 0xFF;
            }
            throw new IOException("the body source failed after its first chunk");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (position >= first.length) {
                throw new IOException("the body source failed after its first chunk");
            }
            int count = Math.min(len, first.length - position);
            System.arraycopy(first, position, b, off, count);
            position += count;
            return count;
        }
    }
}
