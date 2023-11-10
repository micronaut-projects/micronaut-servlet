package io.micronaut.servlet.undertow

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Error
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.http.annotation.Status
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import reactor.core.publisher.Flux
import spock.lang.Specification

@MicronautTest
@Property(name = "spec.name", value = "UndertowErrorSpec")
class UndertowErrorSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    void "error handler is correctly used"() {
        when:
        def result = client.toBlocking().exchange("/errors/local", String)

        then: 'We get the correct response'
        result.body() == "Handled bad things"

        and: 'The content type is correct as per the @Produces annotation'
        result.header(HttpHeaders.CONTENT_TYPE).startsWith(MediaType.TEXT_PLAIN)
    }

    void "error that occurs with streaming response results in client receiving an error"() {
        when:
        client.toBlocking().exchange("/errors/stream-immediate", String)

        then:
        HttpClientResponseException ex = thrown()
        ex.status == HttpStatus.INTERNAL_SERVER_ERROR
        ex.response.body.orElseThrow() == "Internal Server Error: Immediate error"
    }

    void "error that occurs with streaming response after data sent results in client receiving incomplete data"() {
        when:
        client.toBlocking().exchange("/errors/stream-delayed", Integer[].class)

        then:
        HttpClientResponseException ex = thrown()
        ex.status == HttpStatus.OK
        ex.message.contains("Unexpected end-of-input")
    }

    @Requires(property = "spec.name", value = "UndertowErrorSpec")
    @Controller("/errors")
    static class ErrorController {

        @Get("/local")
        @Produces(MediaType.APPLICATION_PDF)
        String localHandler() {
            throw new AnotherException("bad things")
        }

        @Get("/stream-immediate")
        Flux<String> streamingImmediateError() {
            return Flux.error(new IllegalStateException("Immediate error"))
        }

        @Get("/stream-delayed")
        Flux<Integer> streamingDelayedError() {
            return Flux.range(1, 5).map(data -> {
                if (data == 3) {
                    throw new IllegalStateException("Delayed error")
                }
                return data
            })
        }

        @Error
        @Status(HttpStatus.OK)
        @Produces(MediaType.TEXT_PLAIN)
        String localHandler(AnotherException throwable) {
            "Handled $throwable.message"
        }
    }

    static class AnotherException extends RuntimeException {
        AnotherException(String badThings) {
            super(badThings);
        }
    }
}
