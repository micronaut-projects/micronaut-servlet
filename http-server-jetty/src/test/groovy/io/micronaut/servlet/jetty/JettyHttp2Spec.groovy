package io.micronaut.servlet.jetty

import groovy.transform.EqualsAndHashCode
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Introspected
import io.micronaut.http.*
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.HttpVersionSelection
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.util.environment.OperatingSystem

@MicronautTest
@Property(name = 'spec.name', value = 'JettyHttp2PostSpec')
@Property(name = "micronaut.server.http-version", value = "2.0")
@Property(name = "micronaut.ssl.enabled", value = "true")
@Property(name = "micronaut.ssl.build-self-signed", value = "true")
@spock.lang.Requires({ os.family != OperatingSystem.Family.MAC_OS })
class JettyHttp2Spec extends Specification {
    @Inject
    @Client(
            value = "/",
            alpnModes = HttpVersionSelection.ALPN_HTTP_2)
    HttpClient client

    @PendingFeature(reason = "Conscript configuration not supported")
    void "test simple post request with JSON over http/2"() {
        given:
        def book = new Book(title: "The Stand", pages: 1000)

        when:
        HttpResponse<Book> response = client.toBlocking().exchange(
                HttpRequest.POST("/post/simple", book)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),
                Book
        )
        Optional<Book> body = response.getBody()

        then:
        response.status == HttpStatus.OK
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.contentLength == 34
        body.isPresent()
        body.get() instanceof Book
        body.get() == book
    }

    @Introspected
    @EqualsAndHashCode
    static class Book {
        String title
        Integer pages
    }

    @Requires(property = 'spec.name', value = 'JettyHttp2PostSpec')
    @Controller('/post')
    static class PostController {

        @Post('/simple')
        Book simple(HttpRequest<?> request, @Body Book book, @Header String contentType, @Header long contentLength, @Header accept, @Header('X-My-Header') custom) {
            assert request.httpVersion == HttpVersion.HTTP_2_0
            assert contentType == MediaType.APPLICATION_JSON
            assert contentLength == 34
            assert accept == MediaType.APPLICATION_JSON
            assert custom == 'Foo'
            return book
        }
    }
}
