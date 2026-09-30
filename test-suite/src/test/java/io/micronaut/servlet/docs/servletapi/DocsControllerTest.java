package io.micronaut.servlet.docs.servletapi;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.multipart.MultipartBody;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the controller shown in the Servlet API section of the guide, so the documented
 * sources are real, compiled and covered.
 */
@Property(name = "spec.name", value = "DocsControllerTest")
@MicronautTest
class DocsControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void theServletRequestAndResponseCanBeInjected() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/docs/hello?name=Fred"), String.class);

        assertEquals(HttpStatus.ACCEPTED, response.status());
        assertEquals("Hello Fred", response.body());
    }

    @Test
    void readableAndWritableSimplifyIo() {
        String body = client.toBlocking().retrieve(HttpRequest.POST("/docs/writable", "Fred").contentType(MediaType.TEXT_PLAIN), String.class);

        assertEquals("Hello Fred", body);
    }

    @Test
    void partsCanBeInjectedWithPart() {
        MultipartBody body = MultipartBody.builder()
                .addPart("attribute", "value")
                .addPart("one", "one.json", MediaType.APPLICATION_JSON_TYPE, "{\"name\":\"Fred\",\"age\":20}".getBytes(StandardCharsets.UTF_8))
                .addPart("two", "two.txt", MediaType.TEXT_PLAIN_TYPE, "text".getBytes(StandardCharsets.UTF_8))
                .addPart("three", "three.bin", MediaType.APPLICATION_OCTET_STREAM_TYPE, new byte[]{1, 2, 3})
                .addPart("four", "four.txt", MediaType.TEXT_PLAIN_TYPE, "raw".getBytes(StandardCharsets.UTF_8))
                .addPart("five", "five.txt", MediaType.TEXT_PLAIN_TYPE, "part".getBytes(StandardCharsets.UTF_8))
                .build();

        String result = client.toBlocking().retrieve(HttpRequest.POST("/docs/multipart", body)
                .contentType(MediaType.MULTIPART_FORM_DATA_TYPE), String.class);

        assertEquals("Ok", result);
    }
}
