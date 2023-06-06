package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.http.annotation.QueryValue
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
@Property(name = "micronaut.server.context-path", value = CONTEXT_PATH)
@Property(name = "spec.name", value = SPEC_NAME)
class JettyContextPathSpec extends Specification {

    static final String SPEC_NAME = "JettyContextPathSpec"
    static final String CONTEXT_PATH = "/test"

    @Inject
    @Client("/")
    HttpClient client

    void "context-path is supported"() {
        when:
        def request = HttpRequest.GET(CONTEXT_PATH + "?name=Fred")
        String response = client.toBlocking().retrieve(request)

        then:
        response == "OK Fred"
    }

    @Controller
    static class TestController {

        @Get
        @Produces(MediaType.TEXT_PLAIN)
        String index(@QueryValue String name) {
            "OK $name"
        }
    }
}
