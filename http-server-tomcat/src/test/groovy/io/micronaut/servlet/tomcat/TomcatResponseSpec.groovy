package io.micronaut.servlet.tomcat

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import spock.lang.Specification

import javax.inject.Inject

@MicronautTest
class TomcatResponseSpec extends Specification {
    @Inject
    @Client("/")
    RxHttpClient client

    void 'test response'() {
        when:
        def response = client.exchange(HttpRequest.POST("/response/test/bar", "content"), String).blockingFirst()

        then:
        response.status() == HttpStatus.OK
        response.body() == null
    }

    @Controller("/response/test")
    static class FooController {

        @Post("/bar")
        public HttpResponse bar(@Body String json) {
            System.out.println(json);

            return HttpResponse.ok();
        }
    }
}
