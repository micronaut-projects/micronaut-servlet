package io.micronaut.servlet.tomcat

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import spock.lang.Issue
import spock.lang.Specification

import jakarta.inject.Inject

@Property(name = 'spec.name', value = 'TomcatResponseSpec')
@MicronautTest
class TomcatResponseSpec extends Specification {
    @Inject
    @Client("/")
    HttpClient client

    @Issue("https://github.com/micronaut-projects/micronaut-servlet/issues/177")
    void 'verify controller with HttpResponse as returned type is handled correctly'() {
        when:
        def response = client.toBlocking().exchange(HttpRequest.POST("/response/test/bar", "content"), String)

        then:
        response.status() == HttpStatus.OK
        response.body() == null
    }

    @Issue("https://github.com/micronaut-projects/micronaut-servlet/issues/298")
    void 'verify that controller\'s HttpRequest object contains valid URI'() {
        when:
        def response = client.toBlocking().exchange(HttpRequest.GET("/response/test/hello?a=1&b=2"), String)

        then:
        response.status() == HttpStatus.OK
        response.body() == "/response/test/hello?a=1&b=2"
    }

    @Requires(property = 'spec.name', value = 'TomcatResponseSpec')
    @Controller("/response/test")
    static class FooController {

        @Post("/bar")
        HttpResponse bar(@Body String json) {
            HttpResponse.ok()
        }

        @Get("/hello")
        String hello(HttpRequest<?> httpRequest) {
            return httpRequest.getUri().toString();
        }
    }
}
