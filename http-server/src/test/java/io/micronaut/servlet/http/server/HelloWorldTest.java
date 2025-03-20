package io.micronaut.servlet.http.server;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Property(name = "spec.name", value = "HelloWorldTest")
@MicronautTest
class HelloWorldTest {

    @Test
    void testHelloWorld(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        String json = assertDoesNotThrow(() -> client.retrieve("/hello/world", String.class));
        assertEquals("""
        {"message":"Hello World"}""", json);
    }

    @Secured(SecurityRule.IS_ANONYMOUS)
    @Requires(property = "spec.name", value = "HelloWorldTest")
    @Controller("/hello")
    static class HelloWorldController {
        @Get("/world")
        String index() {
            return "{\"message\":\"Hello World\"}";
        }
    }
}
