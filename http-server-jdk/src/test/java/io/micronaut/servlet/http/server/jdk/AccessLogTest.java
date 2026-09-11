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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in server can write an access log, as the other runtimes do, in the common log format.
 */
class AccessLogTest {

    @Test
    void requestsAreLoggedInCommonLogFormatWhenEnabled() throws Exception {
        Logger accessLogger = (Logger) LoggerFactory.getLogger("test-access-log");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
        accessLogger.setLevel(Level.INFO);

        EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "AccessLogTest",
            "micronaut.server.jdk.access-logger.enabled", "true",
            "micronaut.server.jdk.access-logger.logger-name", "test-access-log",
            "micronaut.server.jdk.access-logger.exclusions", List.of("/logged/health.*")
        ));
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(server.getURI().resolve("/logged/hello?name=x")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            client.send(HttpRequest.newBuilder(server.getURI().resolve("/logged/health")).build(),
                HttpResponse.BodyHandlers.ofString());

            List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(1, lines.size(), "the excluded path must not be logged: " + lines);
            String line = lines.get(0);
            assertTrue(line.startsWith("127.0.0.1 - - ["), line);
            assertTrue(line.contains("\"GET /logged/hello?name=x HTTP/1.1\" 200 5"), line);
            assertFalse(line.contains("health"), line);
        } finally {
            server.close();
            accessLogger.detachAppender(appender);
        }
    }

    @Requires(property = "spec.name", value = "AccessLogTest")
    @Controller("/logged")
    @Secured(SecurityRule.IS_ANONYMOUS)
    static class LoggedController {

        @Get("/hello")
        String hello() {
            return "hello";
        }

        @Get("/health")
        String health() {
            return "up";
        }
    }
}
