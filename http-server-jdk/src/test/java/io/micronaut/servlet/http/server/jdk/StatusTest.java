package io.micronaut.servlet.http.server.jdk;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Property(name = "spec.name", value = "StatusTest")
@MicronautTest
class StatusTest {

    @Test
    void testTeapotStatus(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () -> client.exchange("/status"));
        assertEquals(HttpStatus.I_AM_A_TEAPOT, ex.getStatus());
    }

    @Secured(SecurityRule.IS_ANONYMOUS)
    @Requires(property = "spec.name", value = "StatusTest")
    @Controller("/status")
    static class HelloWorldController {
        @Get
        HttpStatus index() {
            return HttpStatus.I_AM_A_TEAPOT;
        }
    }
}

