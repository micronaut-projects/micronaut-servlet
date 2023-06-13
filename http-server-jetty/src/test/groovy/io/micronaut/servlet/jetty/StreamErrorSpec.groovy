package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import spock.lang.Specification

@MicronautTest
@Property(name = "spec.name", value = SPEC_NAME)
class StreamErrorSpec extends Specification {

    static final String SPEC_NAME = "StreamErrorSpec"

    @Inject
    @Client("/")
    HttpClient client;

    void "status error as first item"() {
        when:
        def response = client.toBlocking().exchange(HttpRequest.GET("/stream-error/status-error-as-first-item"), String)

        then:
        def e = thrown(HttpClientResponseException)
        e.response.status == HttpStatus.NOT_FOUND
        e.response.body() == "foo"
    }

    void "immediate status error"() {
        when:
        def response = client.toBlocking().exchange(HttpRequest.GET("/stream-error/status-error-immediate"), String)

        then:
        def e = thrown(HttpClientResponseException)
        e.response.status == HttpStatus.NOT_FOUND
        e.response.body() == "foo"
    }

    @Controller("/stream-error")
    @Requires(property = "spec.name", value = SPEC_NAME)
    static class StreamController {

        @Get(uri = "/status-error-as-first-item")
        Publisher<String> statusErrorAsFirstItem() {
            return Flux.error(new HttpStatusException(HttpStatus.NOT_FOUND, (Object) "foo"));
        }

        @Get(uri = "/status-error-immediate")
        Publisher<String> statusErrorImmediate() {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, (Object) "foo");
        }
    }
}
