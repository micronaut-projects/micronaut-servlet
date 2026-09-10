package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.HttpVersion
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.HttpVersionSelection
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import reactor.core.publisher.Flux
import spock.lang.Specification

/**
 * HTTP/2 carries the payload in DATA frames and forbids Transfer-Encoding, so neither framing header says whether a
 * body follows. A body must still bind when both are absent, and a request that genuinely has none must still bind
 * empty rather than failing to decode.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'JettyHttp2BodyFramingSpec')
@Property(name = "micronaut.server.http-version", value = "2.0")
class JettyHttp2BodyFramingSpec extends Specification {

    @Inject
    @Client(
        value = "/",
        alpnModes = HttpVersionSelection.ALPN_HTTP_2,
        plaintextMode = HttpVersionSelection.PlaintextMode.H2C)
    HttpClient client

    void "a streamed body with no Content-Length still binds over h2c"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.POST("/h2-framing/echo", Flux.just("streamed body"))
                .contentType(MediaType.TEXT_PLAIN)
                .accept(MediaType.TEXT_PLAIN),
            String
        )

        then:
        response.status == HttpStatus.OK
        response.body() == "streamed body"
    }

    void "a request with no body binds empty over h2c"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.GET("/h2-framing/no-body").accept(MediaType.TEXT_PLAIN),
            String
        )

        then:
        response.status == HttpStatus.OK
        response.body() == "empty"
    }

    @Requires(property = 'spec.name', value = 'JettyHttp2BodyFramingSpec')
    @Controller('/h2-framing')
    static class BodyFramingController {

        @Post(value = '/echo', consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_PLAIN)
        String echo(HttpRequest<String> request) {
            assert request.httpVersion == HttpVersion.HTTP_2_0
            assert request.contentLength == -1
            // resolving the body through the request is the path that reads framing headers to decide whether one
            // was sent at all; @Body would be bound by the argument binder instead
            return request.getBody().orElse("MISSING")
        }

        @Get(value = '/no-body', produces = MediaType.TEXT_PLAIN)
        String noBody(HttpRequest<String> request) {
            assert request.httpVersion == HttpVersion.HTTP_2_0
            return request.getBody().orElse("empty")
        }
    }
}
