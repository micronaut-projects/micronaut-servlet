
package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Consumes
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
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
        client.flux('1234').blockFirst()
        client.flux('notthere').collectList().block() == []
    }

    void "test 404 handling with Mono"() {

        // TODO[moss]: Why must I `onErrorResume` on second expectation here?
        expect:
        client.maybe('1234').block()
        client.maybe('notthere').onErrorResume(t -> Mono.empty()).block() == null
    }

    @Requires(property = 'spec.name', value = 'JettyNotFoundSpec')
    @Client('/not-found')
    static interface InventoryClient {
        @Consumes(MediaType.TEXT_PLAIN)
        @Get('/maybe/{isbn}')
        Mono<Boolean> maybe(String isbn)

        @Get(value = '/flux/{isbn}', processes = MediaType.TEXT_EVENT_STREAM)
        Flux<Boolean> flux(String isbn)
    }

    @Requires(property = 'spec.name', value = 'JettyNotFoundSpec')
    @Controller(value = "/not-found", produces = MediaType.TEXT_PLAIN)
    static class InventoryController {
        Map<String, Boolean> stock = [
                '1234': true
        ]

        @Get('/maybe/{isbn}')
        Mono<Boolean> maybe(String isbn) {
            Boolean value = stock[isbn]
            if (value != null) {
                return Mono.just(value)
            }
            // TODO[moss]: Maybe try Mono.just(null) ?
            return Mono.empty()
        }

        @Get(value = '/flux/{isbn}', processes = MediaType.TEXT_EVENT_STREAM)
        Flux<Boolean> flux(String isbn) {
            Boolean value = stock[isbn]
            if (value != null) {
                return Flux.just(value)
            }
            return Flux.empty()
        }
    }
}
