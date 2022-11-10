package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Error
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import reactor.core.publisher.Mono
import spock.lang.Specification

import jakarta.inject.Inject

import static io.micronaut.http.HttpHeaders.*

@MicronautTest
@Property(name = "spec.name", value = "JettyCorsSpec")
class JettyCorsSpec extends Specification implements TestPropertyProvider {

    @Inject
    @Client("/")
    HttpClient rxClient

    void "test non cors request"() {
        when:
        def response = rxClient.toBlocking().exchange('/test')
        Set<String> headerNames = response.getHeaders().names()

        then:
        response.status == HttpStatus.NO_CONTENT
        response.contentLength == -1
        headerNames.size() == 2
        // Client is now keep-alive so we don't get the connection header
        !headerNames.contains(CONNECTION)
        headerNames.contains(DATE)
        headerNames.contains(SERVER)
    }

    void "test cors request without configuration"() {
        given:
        def response = rxClient.toBlocking().exchange(
                HttpRequest.GET('/test')
                        .header(ORIGIN, 'fooBar.com')
        )

        when:
        Set<String> headerNames = response.headers.names()

        then:
        response.status == HttpStatus.NO_CONTENT
        headerNames.size() == 2
        // Client is now keep-alive so we don't get the connection header
        !headerNames.contains(CONNECTION)
        headerNames.contains(DATE)
        headerNames.contains(SERVER)
    }

    void "test cors request with a controller that returns map"() {
        given:
        def response = rxClient.toBlocking().exchange(
                HttpRequest.GET('/test/arbitrary')
                        .header(ORIGIN, 'foo.com')
        )

        when:
        Set<String> headerNames = response.headers.names()

        then:
        response.status == HttpStatus.OK
        response.header(ACCESS_CONTROL_ALLOW_ORIGIN) == 'foo.com'
        response.header(VARY) == ORIGIN
        !headerNames.contains(ACCESS_CONTROL_MAX_AGE)
        !headerNames.contains(ACCESS_CONTROL_ALLOW_HEADERS)
        !headerNames.contains(ACCESS_CONTROL_ALLOW_METHODS)
        !headerNames.contains(ACCESS_CONTROL_EXPOSE_HEADERS)
        response.header(ACCESS_CONTROL_ALLOW_CREDENTIALS) == 'true'
    }

    void "test cors request with controlled method"() {
        given:
        def response = rxClient.toBlocking().exchange(
                HttpRequest.GET('/test')
                        .header(ORIGIN, 'foo.com')
        )

        when:
        Set<String> headerNames = response.headers.names()

        then:
        response.status == HttpStatus.NO_CONTENT
        response.header(ACCESS_CONTROL_ALLOW_ORIGIN) == 'foo.com'
        response.header(VARY) == ORIGIN
        !headerNames.contains(ACCESS_CONTROL_MAX_AGE)
        !headerNames.contains(ACCESS_CONTROL_ALLOW_HEADERS)
        !headerNames.contains(ACCESS_CONTROL_ALLOW_METHODS)
        !headerNames.contains(ACCESS_CONTROL_EXPOSE_HEADERS)
        response.header(ACCESS_CONTROL_ALLOW_CREDENTIALS) == 'true'
    }

    void "test cors request with controlled headers"() {
        given:
        def response = rxClient.toBlocking().exchange(
                HttpRequest.GET('/test')
                        .header(ORIGIN, 'bar.com')
                        .header(ACCEPT, 'application/json')

        )

        when:
        Set<String> headerNames = response.headers.names()

        then:
        response.code() == HttpStatus.NO_CONTENT.code
        response.header(ACCESS_CONTROL_ALLOW_ORIGIN) == 'bar.com'
        response.header(VARY) == ORIGIN
        !headerNames.contains(ACCESS_CONTROL_MAX_AGE)
        !headerNames.contains(ACCESS_CONTROL_ALLOW_HEADERS)
        !headerNames.contains(ACCESS_CONTROL_ALLOW_METHODS)
        response.headers.getAll(ACCESS_CONTROL_EXPOSE_HEADERS) == ['x', 'y']
        !headerNames.contains(ACCESS_CONTROL_ALLOW_CREDENTIALS)
    }

    void "test cors request with invalid method"() {
        when:
        rxClient.toBlocking().exchange(
                HttpRequest.POST('/test', [:])
                        .header(ORIGIN, 'foo.com')

        )

        then:
        def e = thrown(HttpClientResponseException)
        def response = e.response

        when:
        Set<String> headerNames = response.headers.names()

        then:
        response.code() == HttpStatus.FORBIDDEN.code
        // Client is now keep-alive so we don't get the connection header
        !headerNames.contains(CONNECTION)
        headerNames.contains(DATE)
        headerNames.contains(SERVER)
    }

    void "test cors request with invalid header"() {
        given:
        def response = rxClient.toBlocking().exchange(
                HttpRequest.GET('/test')
                        .header(ORIGIN, 'bar.com')
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, 'Foo, Accept')

        )

        expect: "it passes through because only preflight requests check allowed headers"
        response.code() == HttpStatus.NO_CONTENT.code
    }

    void "test preflight request with invalid header"() {
        when:
        rxClient.toBlocking().exchange(
                HttpRequest.OPTIONS('/test')
                        .header(ACCESS_CONTROL_REQUEST_METHOD, 'GET')
                        .header(ORIGIN, 'bar.com')
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, 'Foo, Accept')

        )

        then: "it fails because preflight requests check allowed headers"
        def e = thrown(HttpClientResponseException)
        e.response.code() == HttpStatus.FORBIDDEN.code
    }

    void "test preflight request with invalid method"() {
        when:
        rxClient.toBlocking().exchange(
                HttpRequest.OPTIONS('/test')
                        .header(ACCESS_CONTROL_REQUEST_METHOD, 'POST')
                        .header(ORIGIN, 'foo.com')

        )

        then:
        def e = thrown(HttpClientResponseException)
        e.response.code() == HttpStatus.FORBIDDEN.code
    }

    void "test preflight request with controlled method"() {
        given:
        def response = rxClient.toBlocking().exchange(
                HttpRequest.OPTIONS('/test')
                        .header(ACCESS_CONTROL_REQUEST_METHOD, 'GET')
                        .header(ORIGIN, 'foo.com')
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, 'Foo, Bar')

        )

        def headerNames = response.headers.names()

        expect:
        response.code() == HttpStatus.OK.code
        response.header(ACCESS_CONTROL_ALLOW_METHODS) == 'GET'
        response.headers.getAll(ACCESS_CONTROL_ALLOW_HEADERS) == ['Foo', 'Bar']
        !headerNames.contains(ACCESS_CONTROL_MAX_AGE)
        response.header(ACCESS_CONTROL_ALLOW_ORIGIN) == 'foo.com'
        response.header(VARY) == ORIGIN
        !headerNames.contains(ACCESS_CONTROL_EXPOSE_HEADERS)
        response.header(ACCESS_CONTROL_ALLOW_CREDENTIALS) == 'true'
    }

    void "test preflight request with controlled headers"() {

        given:
        def response = rxClient.toBlocking().exchange(
                HttpRequest.OPTIONS('/test')
                        .header(ACCESS_CONTROL_REQUEST_METHOD, 'POST')
                        .header(ORIGIN, 'bar.com')
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, 'Accept')
        )

        def headerNames = response.headers.names()

        expect:
        response.code() == HttpStatus.OK.code
        response.header(ACCESS_CONTROL_ALLOW_METHODS) == 'POST'
        response.headers.getAll(ACCESS_CONTROL_ALLOW_HEADERS) == ['Accept']
        response.header(ACCESS_CONTROL_MAX_AGE) == '150'
        response.header(ACCESS_CONTROL_ALLOW_ORIGIN) == 'bar.com'
        response.header(VARY) == ORIGIN
        response.headers.getAll(ACCESS_CONTROL_EXPOSE_HEADERS) == ['x', 'y']
        !headerNames.contains(ACCESS_CONTROL_ALLOW_CREDENTIALS)
    }

    void "test control headers are applied to error response routes"() {
        when:
        rxClient.toBlocking().exchange(
                HttpRequest.GET('/test/error')
                        .header(ORIGIN, 'foo.com')
        )

        then:
        def ex = thrown(HttpClientResponseException)
        ex.response.status == HttpStatus.BAD_REQUEST
        ex.response.header(ACCESS_CONTROL_ALLOW_ORIGIN) == 'foo.com'
        ex.response.header(VARY) == ORIGIN
    }

    void "test control headers are applied to error responses with no handler"() {
        when:
        rxClient.toBlocking().exchange(
                HttpRequest.GET('/test/error-checked')
                        .header(ORIGIN, 'foo.com')
        )

        then:
        def ex = thrown(HttpClientResponseException)
        ex.response.status == HttpStatus.INTERNAL_SERVER_ERROR
        ex.response.header(ACCESS_CONTROL_ALLOW_ORIGIN) == 'foo.com'
        ex.response.header(VARY) == ORIGIN
    }

    void "test control headers are applied to http error responses"() {
        when:
        rxClient.toBlocking().exchange(
                HttpRequest.GET('/test/error-response')
                        .header(ORIGIN, 'foo.com')
        )

        then:
        def ex = thrown(HttpClientResponseException)
        ex.response.status == HttpStatus.BAD_REQUEST
        ex.response.header(ACCESS_CONTROL_ALLOW_ORIGIN) == 'foo.com'
        ex.response.headers.getAll(ACCESS_CONTROL_ALLOW_ORIGIN).size() == 1
        ex.response.header(VARY) == ORIGIN
    }

    @Override
    Map<String, Object> getProperties() {
        ['micronaut.server.cors.enabled': true,
         'micronaut.server.cors.configurations.foo.allowedOrigins': ['foo.com'],
         'micronaut.server.cors.configurations.foo.allowedMethods': ['GET'],
         'micronaut.server.cors.configurations.foo.maxAge': -1,
         'micronaut.server.cors.configurations.bar.allowedOrigins': ['bar.com'],
         'micronaut.server.cors.configurations.bar.allowedHeaders': ['Content-Type', 'Accept'],
         'micronaut.server.cors.configurations.bar.exposedHeaders': ['x', 'y'],
         'micronaut.server.cors.configurations.bar.maxAge': 150,
         'micronaut.server.cors.configurations.bar.allowCredentials': false,
         'micronaut.server.dateHeader': false]
    }

    @Controller('/test')
    @Requires(property = 'spec.name', value = 'JettyCorsSpec')
    static class TestController {

        @Get
        HttpResponse index() {
            HttpResponse.noContent()
        }

        @Post
        HttpResponse post() {
            HttpResponse.noContent()
        }

        @Get('/arbitrary')
        Map arbitrary() {
            [some: 'data']
        }

        @Get("/error")
        String error() {
            throw new RuntimeException("error")
        }

        @Get("/error-checked")
        String errorChecked() {
            throw new IOException("error")
        }

        @Get("/error-response")
        HttpResponse errorResponse() {
            HttpResponse.badRequest()
        }

        @Error(exception = RuntimeException)
        HttpResponse onError() {
            HttpResponse.badRequest()
        }
    }
}
