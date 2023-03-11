package io.micronaut.servlet.tomcat

import groovy.transform.EqualsAndHashCode
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Introspected
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Status
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.validation.Validated
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import spock.lang.Issue
import spock.lang.Specification


@MicronautTest
@Property(name = 'spec.name', value = 'TomcatValidationStatusSpec')
class TomcatValidationStatusSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    void 'test response status matches @Status annotation'() {
        given:
        Book book = new Book(title: 'The Stand', pages: 400)

        when:
        HttpResponse response = client.toBlocking().exchange(
                HttpRequest.POST('/validation-status-test/create', book)
                        .accept(MediaType.APPLICATION_JSON_TYPE),
                Book
        )

        then:
        noExceptionThrown()
        response.status == HttpStatus.CREATED
    }

    @Issue('https://github.com/micronaut-projects/micronaut-servlet/issues/231')
    void 'test failed constraint status not overridden by @Status'() {
        given:
        Book book = new Book(title: '', pages: 400)

        when:
        client.toBlocking().exchange(
                HttpRequest.POST('/validation-status-test/create', book)
                        .accept(MediaType.APPLICATION_JSON_TYPE),
                Book
        )

        then:
        def e = thrown(HttpClientResponseException)
        e.response.status == HttpStatus.BAD_REQUEST
    }

    @Requires(property = 'spec.name', value = 'TomcatValidationStatusSpec')
    @Validated
    @Controller('/validation-status-test')
    static class StatusController {
        Map<String, Book> books = [:]

        @Post('/create')
        @Status(HttpStatus.CREATED)
        Book create(@Valid @NotNull @Body Book book) {
            books[book.title] = book
            book
        }
    }

    @Introspected
    @EqualsAndHashCode
    static class Book {
        @NotBlank
        @NotNull
        String title

        Integer pages
    }

}
