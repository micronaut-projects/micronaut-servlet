package io.micronaut.servlet.http.server.jdk;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.ServerHttpRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    @Test
    void smallFormIsBoundOnTheThreadOfTheContainer(@Client("/") HttpClient httpClient) {
        // the JDK server has no asynchronous reads: a small form is read inline, not on the IO executor
        SaveController.THREAD.set(null);
        HttpRequest<?> request = HttpRequest.POST("/book/save", "title=T&pages=1").contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        assertEquals("{\"title\":\"T\",\"pages\":1}", httpClient.toBlocking().retrieve(request));
        String thread = SaveController.THREAD.get();
        assertNotNull(thread);
        // the server's own thread, not the IO executor that reads a streamed body
        assertTrue(thread.startsWith("micronaut-jdk-server"), thread);
    }

    @Test
    void formIsBoundAfterAFilterReadACopyOfIt(@Client("/") HttpClient httpClient) {
        HttpRequest<?> request = HttpRequest.POST("/book/save?x=1", "title=Copied&pages=2")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
            .header("X-Read-Copy", "true");
        HttpResponse<String> response = httpClient.toBlocking().exchange(request, String.class);
        assertEquals("{\"title\":\"Copied\",\"pages\":2}", response.body());
        assertEquals("title=Copied&pages=2", response.header("X-Copy"));
    }

    @Test
    void formFieldsAndQueryParametersAreBound(@Client("/") HttpClient httpClient) {
        HttpRequest<?> request = HttpRequest.POST("/book/fields?source=query", "title=a+b%26c&tag=one&tag=two")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        assertEquals("a b&c [one, two] query", httpClient.toBlocking().retrieve(request));
    }

    @Requires(property = "spec.name", value = "FormSubmissionTest")
    @ServerFilter("/book/**")
    static class CopyFilter {
        @RequestFilter
        CompletableFuture<@Nullable HttpResponse<?>> readCopy(HttpRequest<?> request) {
            if (!request.getHeaders().contains("X-Read-Copy") || !(request instanceof ServerHttpRequest<?> server)) {
                return CompletableFuture.completedFuture(null);
            }
            return server.byteBody().split(io.micronaut.http.body.ByteBody.SplitBackpressureMode.FASTEST).buffer()
                .thenApply(bytes -> {
                    try (bytes) {
                        SaveController.COPY.set(bytes.toString(StandardCharsets.UTF_8));
                    }
                    return null;
                });
        }
    }

    @Requires(property = "spec.name", value = "FormSubmissionTest")
    @Controller("/book")
    static class SaveController {

        @Secured(SecurityRule.IS_ANONYMOUS)
        @Consumes({"application/x-www-form-urlencoded"})
        @Post("/save")
        HttpResponse<Book> save(@Body Book book, HttpRequest<?> request) {
            THREAD.set(Thread.currentThread().getName());
            HttpResponse<Book> response = HttpResponse.ok(book);
            String copy = COPY.getAndSet(null);
            if (copy != null && request.getHeaders().contains("X-Read-Copy")) {
                ((io.micronaut.http.MutableHttpResponse<Book>) response).header("X-Copy", copy);
            }
            return response;
        }

        @Secured(SecurityRule.IS_ANONYMOUS)
        @Consumes({"application/x-www-form-urlencoded"})
        @Post("/fields")
        String fields(String title, java.util.List<String> tag, @io.micronaut.http.annotation.QueryValue String source) {
            return title + " " + tag + " " + source;
        }

        static final AtomicReference<@Nullable String> THREAD = new AtomicReference<>();
        static final AtomicReference<@Nullable String> COPY = new AtomicReference<>();
    }

    @Serdeable
    static record Book(@NonNull String title, @Nullable Integer pages) {
        Book(@NonNull String title, @Nullable Integer pages) {
            this.title = title;
            this.pages = pages;
        }

        @Override
        public @NonNull String title() {
            return this.title;
        }

        @Override
        public @Nullable Integer pages() {
            return this.pages;
        }
    }
}
