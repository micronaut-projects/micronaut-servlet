package io.micronaut.servlet.jetty

import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.annotation.MicronautTest
import io.micronaut.test.annotation.MockBean
import spock.lang.Specification

import javax.inject.Inject

@MicronautTest
class JettyHttpStatusSpec extends Specification {

    @Inject
    @Client("/")
    RxHttpClient client

    void 'test return http status'() {
        expect:
        client.exchange('/status-test').blockingFirst()
            .status() == HttpStatus.ACCEPTED
    }

    @Controller('/status-test')
    @MockBean
    static class StatusController {
        @Get
        HttpStatus index() {
            return HttpStatus.ACCEPTED
        }
    }
}
