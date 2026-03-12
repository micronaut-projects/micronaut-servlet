package io.micronaut.servlet.http.server.jdk;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Property(name = "spec.name", value = "QuotedQueryParameterTest")
@MicronautTest
class QuotedQueryParameterTest {

    @Test
    void quotedQueryValueIsBound(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        HttpRequest<?> request = HttpRequest.GET("/endpoint")
            .uri(uriBuilder -> uriBuilder.queryParam("query", "\"quoted\""));

        String body = assertDoesNotThrow(() -> client.retrieve(request, String.class));
        assertEquals("response-text", body);
    }

    @Secured(SecurityRule.IS_ANONYMOUS)
    @Requires(property = "spec.name", value = "QuotedQueryParameterTest")
    @Controller
    static class Endpoint {
        @Get("/endpoint")
        String endpoint(@QueryValue("query") String query) {
            assertEquals("\"quoted\"", query);
            return "response-text";
        }
    }
}
