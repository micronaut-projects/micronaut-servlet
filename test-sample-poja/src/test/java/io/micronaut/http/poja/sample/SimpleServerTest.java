package io.micronaut.http.poja.sample;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.poja.sample.model.Cactus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
@TestMethodOrder(MethodOrderer.MethodName.class)
public class SimpleServerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void testGetMethod() {
        HttpResponse<?> response = client.toBlocking()
            .exchange(HttpRequest.GET("/").header("Host", "h"));

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, response.getContentType().get());
        assertEquals("Hello, Micronaut Without Netty!\n", response.getBody(String.class).get());
    }

    @Test
    void testInvalidGetMethod() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
                HttpResponse<?> response = client.toBlocking()
                    .exchange(HttpRequest.GET("/test/invalid").header("Host", "h"));
        });

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, e.getResponse().getContentType().get());
        assertTrue(e.getResponse().getBody(String.class).get().length() > 0);
    }

    @Test
    void testDeleteMethod() {
        HttpResponse<?> response = client.toBlocking()
            .exchange(HttpRequest.DELETE("/").header("Host", "h"));

        assertEquals(HttpStatus.OK, response.status());
        response.getBody(String.class).isEmpty();
    }

    @Test
    void testPostMethod() {
        HttpResponse<?> response = client.toBlocking()
            .exchange(HttpRequest.POST("/Andriy", null).header("Host", "h"));

        assertEquals(HttpStatus.CREATED, response.status());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, response.getContentType().get());
        assertEquals("Hello, Andriy\n", response.getBody(String.class).get());
    }

    @Test
    void testPutMethod() {
        HttpResponse<?> response = client.toBlocking()
            .exchange(HttpRequest.PUT("/Andriy", null).header("Host", "h"));

        assertEquals(HttpStatus.OK, response.status());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, response.getContentType().get());
        assertEquals("Hello, Andriy!\n", response.getBody(String.class).get());
    }

    @Test
    void testGetMethodWithSerialization() {
        HttpResponse<?> response = client.toBlocking()
            .exchange(HttpRequest.GET("/cactus").header("Host", "h"));

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getContentType().get());
        assertEquals(new Cactus("green", 1), response.getBody(Cactus.class).get()); ;
    }

}
