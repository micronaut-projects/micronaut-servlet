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
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
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

    @Requires(property = "spec.name", value = "UndertowErrorSpec")
    @Controller("/errors")
    static class ErrorController {

        @Get("/local")
        @Produces(MediaType.APPLICATION_PDF)
        String localHandler() {
            throw new AnotherException("bad things");
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
