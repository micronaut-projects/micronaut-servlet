
package io.micronaut.servlet.jetty


import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Consumes
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.hateoas.JsonError
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.reactivex.Flowable
import io.reactivex.Maybe
import spock.lang.Specification

import javax.inject.Inject

@MicronautTest
@Property(name = 'spec.name', value = 'JettyNotFoundSpec')
class JettyNotFoundSpec extends Specification {

    @Inject
    InventoryClient client

    @Inject
    @Client('/not-found')
    RxHttpClient rxClient

    void "test 404 handling with Flowable"() {
        expect:
        client.flowable('1234').blockingFirst()
        client.flowable('notthere').toList().blockingGet() == []
    }

    void "test 404 handling with Maybe"() {
        expect:
        client.maybe('1234').blockingGet()
        client.maybe('notthere').blockingGet() == null
    }

    void "test 404 handling with Maybe and JsonError"() {
        when:
        rxClient.exchange('/maybe/notthere').blockingFirst()

        then:
        def t = thrown(HttpClientResponseException)
        t.response.getBody(JsonError).get().message.contains 'Not Found'
    }

    @Requires(property = 'spec.name', value = 'JettyNotFoundSpec')
    @Client('/not-found')
    static interface InventoryClient {
        @Consumes(MediaType.TEXT_PLAIN)
        @Get('/maybe/{isbn}')
        Maybe<Boolean> maybe(String isbn)

        @Get(value = '/flowable/{isbn}', processes = MediaType.TEXT_EVENT_STREAM)
        Flowable<Boolean> flowable(String isbn)
    }

    @Requires(property = 'spec.name', value = 'JettyNotFoundSpec')
    @Controller(value = "/not-found", produces = MediaType.TEXT_PLAIN)
    static class InventoryController {
        Map<String, Boolean> stock = [
                '1234': true
        ]

        @Get('/maybe/{isbn}')
        Maybe<Boolean> maybe(String isbn) {
            Boolean value = stock[isbn]
            if (value != null) {
                return Maybe.just(value)
            }
            return Maybe.empty()
        }

        @Get(value = '/flowable/{isbn}', processes = MediaType.TEXT_EVENT_STREAM)
        Flowable<Boolean> flowable(String isbn) {
            Boolean value = stock[isbn]
            if (value != null) {
                return Flowable.just(value)
            }
            return Flowable.empty()
        }
    }
}
