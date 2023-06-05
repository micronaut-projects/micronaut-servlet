package io.micronaut.servlet.jetty.filters

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.RequestFilter
import io.micronaut.http.annotation.ServerFilter
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.filter.FilterContinuation
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

    @ServerFilter
    @Singleton
    @Requires(property = "spec.name", value = RequestFilterBodySpec.SPEC_NAME)
    static class MyServerFilter {

        List<String> events = []

        @RequestFilter("/request-filter/binding")
        void requestFilterBinding(
                @Header String contentType,
                @Body byte[] bytes,
                FilterContinuation<HttpResponse<?>> continuation) {
            events.add("binding " + contentType + " " + new String(bytes, StandardCharsets.UTF_8))
            continuation.proceed()
        }
    }

    @Controller
    @Requires(property = "spec.name", value = SPEC_NAME)
    static class MyController {

        @Post("/request-filter/binding")
        String requestFilterBinding(@Header String contentType, @Body byte[] bytes) {
            contentType + " " + new String(bytes, StandardCharsets.UTF_8)
        }
    }
}
