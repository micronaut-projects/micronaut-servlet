package io.micronaut.servlet.jetty.filters

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.NonNull
import io.micronaut.core.type.Argument
import io.micronaut.core.type.MutableHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.RequestFilter
import io.micronaut.http.annotation.ServerFilter
import io.micronaut.http.body.TypedMessageBodyWriter
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.codec.CodecException
import io.micronaut.http.filter.FilterContinuation
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import spock.lang.Specification

import java.nio.charset.StandardCharsets

@MicronautTest
@Property(name= "spec.name", value = RequestFilterBodySpec.SPEC_NAME)
class RequestFilterBodySpec extends Specification {

    static final String SPEC_NAME = "RequestFilterBodySpec"

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    MyServerFilter filter

    void 'test body access'() {
        when:
        def post = HttpRequest.POST("/request-filter/binding", "{\"foo\":10}").contentType(MediaType.APPLICATION_JSON_TYPE)
        def response = client.toBlocking().retrieve(post)

        then:
        filter.events == ['binding application/json {"foo":10}']
        response == 'application/json {"foo":10}'
    }

    void "test response body mutating filter"() {
        when:
        def post = HttpRequest.GET("/request-filter/mutating")
        def response = client.toBlocking().exchange(post, String.class)

        then:
        response != null
        response.status() == HttpStatus.UNAUTHORIZED
    }

    @ServerFilter
    @Singleton
    @Requires(property = "spec.name", value = RequestFilterBodySpec.SPEC_NAME)
    static class MyServerFilter {

        List<String> events = []

        @RequestFilter("/request-filter/binding")
        @ExecuteOn(TaskExecutors.BLOCKING)
        void requestFilterBinding(
                @Header String contentType,
                @Body byte[] bytes,
                FilterContinuation<HttpResponse<?>> continuation) {
            events.add("binding " + contentType + " " + new String(bytes, StandardCharsets.UTF_8))
            continuation.proceed()
        }

        @RequestFilter("/request-filter/mutating")
        HttpResponse<?> mutatingFilter() {
            return HttpResponse.unauthorized().body(
                    new CustomException("something bad")
            )
        }
    }

    static class CustomException extends RuntimeException {
        CustomException(String message) {
            super(message)
        }
    }

    @Requires(property = "spec.name", value = SPEC_NAME)
    @Singleton
    static class CustomExceptionWriter implements TypedMessageBodyWriter<CustomException> {
        @Override
        Argument<CustomException> getType() {
            return Argument.of(CustomException.class)
        }

        @Override
        void writeTo(@NonNull Argument<CustomException> type, @NonNull MediaType mediaType, CustomException object, @NonNull MutableHeaders outgoingHeaders, @NonNull OutputStream outputStream) throws CodecException {
            outputStream.write(object.getMessage().getBytes(StandardCharsets.UTF_8))
        }
    }

    @Controller
    @Requires(property = "spec.name", value = SPEC_NAME)
    static class MyController {

        @Post("/request-filter/binding")
        String requestFilterBinding(@Header String contentType, @Body byte[] bytes) {
            contentType + " " + new String(bytes, StandardCharsets.UTF_8)
        }

        @Get("/request-filter/mutating")
        String responseMutating() {
            return "ok"
        }
    }
}
