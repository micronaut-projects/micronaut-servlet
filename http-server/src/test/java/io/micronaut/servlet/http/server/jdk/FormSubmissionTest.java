package io.micronaut.servlet.http.server.jdk;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Property(name = "spec.name", value = "FormSubmissionTest")
@MicronautTest
class FormSubmissionTest {
    @Test
    void formSubmissionOptionalInputTypeNumber(@Client("/") HttpClient httpClient)  {
        BlockingHttpClient client = httpClient.toBlocking();
        String body = "title=Building+Microservices&pages=100";
        String expectedJson = "{\"title\":\"Building Microservices\",\"pages\":100}";
        HttpRequest<?> request = HttpRequest.POST("/book/save", body).contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        String json = assertDoesNotThrow(()  -> client.retrieve(request));
        assertEquals(expectedJson, json);
    }

    @Requires(property = "spec.name", value = "FormSubmissionTest")
    @Controller("/book")
    static class SaveController {

        @Secured(SecurityRule.IS_ANONYMOUS)
        @Consumes({"application/x-www-form-urlencoded"})
        @Post("/save")
        Book save(@Body Book book) {
            return book;
        }
    }

    @Serdeable
    static record Book(@NonNull String title, @Nullable Integer pages) {
        Book(@NonNull String title, @Nullable Integer pages) {
            this.title = title;
            this.pages = pages;
        }

        public @NonNull String title() {
            return this.title;
        }

        public @Nullable Integer pages() {
            return this.pages;
        }
    }
}
