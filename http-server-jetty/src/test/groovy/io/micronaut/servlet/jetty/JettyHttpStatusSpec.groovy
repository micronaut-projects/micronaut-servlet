
package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.annotation.MockBean
import spock.lang.Specification

import jakarta.inject.Inject

@MicronautTest
@Property(name = 'spec.name', value = 'JettyHttpStatusSpec')
class JettyHttpStatusSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    void 'test return http status'() {
        expect:
        client.toBlocking().exchange('/status-test')
            .status() == HttpStatus.ACCEPTED
    }

    @Requires(property = 'spec.name', value = 'JettyHttpStatusSpec')
    @Controller('/status-test')
    @MockBean
    static class StatusController {
        @Get
        HttpStatus index() {
            return HttpStatus.ACCEPTED
        }
    }
}
