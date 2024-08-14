
package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.async.annotation.SingleResult
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.Specification

@MicronautTest
@Property(name = 'spec.name', value = 'JettyNotFoundSpec')
class JettyNotFoundSpec extends Specification {

    @Inject
    InventoryClient client

    void "test 404 handling with streaming publisher"() {
        expect:
        Flux.from(client.streaming('1234')).blockFirst()
        Flux.from(client.streaming('notthere')).collectList().block() == []
    }

    void "test 404 handling with not streaming publisher"() {
        when:
        def exists = Mono.from(client.mono('1234')).block()

        then:
        exists

        when:
        Mono.from(client.mono('notthere')).block()

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND

        when:exists = Mono.from(client.flux('1234')).block()

        then:
        exists

        when:
        Mono.from(client.flux('notthere')).block()

        then:
        e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    @Requires(property = 'spec.name', value = 'JettyNotFoundSpec')
    @Client('/not-found')
    static interface InventoryClient {

        @Get(value = '/mono/{isbn}', processes = MediaType.TEXT_PLAIN)
        @SingleResult
        Publisher<Boolean> mono(String isbn)

        @Get(value = '/flux/{isbn}', processes = MediaType.TEXT_PLAIN)
        @SingleResult
        Publisher<Boolean> flux(String isbn)

        @Get(value = '/streaming/{isbn}', processes = MediaType.TEXT_EVENT_STREAM)
        Publisher<Boolean> streaming(String isbn)
    }

    @Requires(property = 'spec.name', value = 'JettyNotFoundSpec')
    @Controller(value = "/not-found", produces = MediaType.TEXT_PLAIN)
    static class InventoryController {
        Map<String, Boolean> stock = [
                '1234': true
        ]

        @Get('/mono/{isbn}')
        @SingleResult
        Publisher<Boolean> maybe(String isbn) {
            Boolean value = stock[isbn]
            if (value != null) {
                return Mono.just(value)
            }
            return Mono.empty()
        }

        @Get(value = '/flux/{isbn}')
        @SingleResult
        Publisher<Boolean> flux(String isbn) {
            Boolean value = stock[isbn]
            if (value != null) {
                return Flux.just(value)
            }
            return Flux.empty()
        }

        @Get(value = '/streaming/{isbn}', processes = MediaType.TEXT_EVENT_STREAM)
        Publisher<Boolean> streaming(String isbn) {
            Boolean value = stock[isbn]
            if (value != null) {
                return Flux.just(value)
            }
            return Flux.empty()
        }

    }
}
