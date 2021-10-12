
package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.async.annotation.SingleResult
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Consumes
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.http.client.exceptions.HttpClientResponseException
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.Specification

@MicronautTest
@Property(name = 'spec.name', value = 'JettyNotFoundSpec')
class JettyNotFoundSpec extends Specification {

    @jakarta.inject.Inject
    InventoryClient client

    void "test 404 handling with Flux"() {
        expect:
        Flux.from(client.stream('1234')).blockFirst()
        Flux.from(client.stream('notthere')).collectList().block() == []
    }

    void "test 404 handling with Mono"() {

        expect:
        Mono.from(client.single('1234')).block()

        when:
        Mono.from(client.single('notthere')).block()

        then:
        def t = thrown(HttpClientResponseException)
        t.status == HttpStatus.NOT_FOUND
    }

    @Requires(property = 'spec.name', value = 'JettyNotFoundSpec')
    @Client('/not-found')
    static interface InventoryClient {
        @Consumes(MediaType.TEXT_PLAIN)
        @Get('/maybe/{isbn}')
        @SingleResult
        Publisher<Boolean> single(String isbn)

        @Get(value = '/flux/{isbn}', processes = MediaType.TEXT_EVENT_STREAM)
        Publisher<Boolean> stream(String isbn)
    }

    @Requires(property = 'spec.name', value = 'JettyNotFoundSpec')
    @Controller(value = "/not-found", produces = MediaType.TEXT_PLAIN)
    static class InventoryController {
        Map<String, Boolean> stock = [
                '1234': true
        ]

        @Get('/maybe/{isbn}')
        @SingleResult
        Publisher<Boolean> maybe(String isbn) {
            Boolean value = stock[isbn]
            if (value != null) {
                return Mono.just(value)
            }
            return Mono.empty()
        }

        @Get(value = '/flux/{isbn}', processes = MediaType.TEXT_EVENT_STREAM)
        Publisher<Boolean> flux(String isbn) {
            Boolean value = stock[isbn]
            if (value != null) {
                return Flux.just(value)
            }
            return Flux.empty()
        }
    }
}
