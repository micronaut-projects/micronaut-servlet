package io.micronaut.servlet.jetty

import io.micronaut.core.type.Argument
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.test.annotation.MicronautTest
import spock.lang.Specification

import javax.inject.Inject

@MicronautTest
class JettyParameterBinding2Spec extends Specification {

    @Inject
    @Client("/")
    RxHttpClient client

    void "test URI parameters"() {

        given:
        def request = HttpRequest.GET("/parameters/uri/Foo")
        def response = client.toBlocking().exchange(request, String)

        expect:
        response.status() == HttpStatus.OK
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.body() == 'Hello Foo'
    }

    void "test invalid HTTP method"() {
        when:
        def request = HttpRequest.POST("/parameters/uri/Foo", "")
        client.toBlocking().exchange(request, String)

        then:
        def e = thrown(HttpClientResponseException)
        def response = e.response
        response.status() == HttpStatus.METHOD_NOT_ALLOWED
        def allow = response.getHeaders().getAll(HttpHeaders.ALLOW)
        allow == ["HEAD,GET"]
    }

    void "test query value"() {

        given:
        def request = HttpRequest.GET("/parameters/query")
        request.parameters.add("q", "Foo")
        def response = client.toBlocking().exchange(request, String)

        expect:
        response.status() == HttpStatus.OK
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.body() == 'Hello Foo'
    }

    void "test all parameters"() {

        given:
        def request = HttpRequest.GET("/parameters/allParams")
        request.parameters.add("name", "Foo")
        request.parameters.add("age", "20")
        def response = client.toBlocking().exchange(request, String)

        expect:
        response.status() == HttpStatus.OK
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.body() == 'Hello Foo 20'
    }

    void "test header value"() {

        given:
        def request = HttpRequest.GET("/parameters/header")
        request.header(HttpHeaders.CONTENT_TYPE, "text/plain;q=1.0")
        def response = client.toBlocking().exchange(request, String)

        expect:
        response.status() == HttpStatus.OK
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.body() == 'Hello text/plain;q=1.0'
    }

    void "test request and response"() {

        given:
        def request = HttpRequest.GET("/parameters/reqAndRes")
        def response = client.toBlocking().exchange(request, String)

        expect:
        response.status() == HttpStatus.ACCEPTED
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.body() == 'Good'
    }

    void "test string body"() {

        given:
        def request = HttpRequest.POST("/parameters/stringBody", "Foo")
        request.header(HttpHeaders.CONTENT_TYPE, "text/plain")
        def response = client.toBlocking().exchange(request, String)

        expect:
        response.status() == HttpStatus.OK
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.body() == 'Hello Foo'
    }


    void "test writable"() {

        given:
        def request = HttpRequest.POST("/parameters/writable", "Foo")
        request.header(HttpHeaders.CONTENT_TYPE, "text/plain")
        def response = client.toBlocking().exchange(request, String)

        expect:
        response.status() == HttpStatus.CREATED
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.body() == 'Hello Foo'
        response.header("Foo") == 'Bar'
    }


    void "test JSON POJO body"() {

        given:
        def json = '{"name":"bar","age":30}'
        def request = HttpRequest.POST("/parameters/jsonBody", json)
        request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
        def response = client.toBlocking().exchange(request, String)

        expect:
        response.status() == HttpStatus.OK
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.body() == json
    }

    void "test JSON POJO body - invalid JSON"() {
        when:
        def json = '{"name":"bar","age":30'
        def request = HttpRequest.POST("/parameters/jsonBody", json)
        request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
        client.toBlocking().exchange(request, Argument.STRING, Argument.STRING)

        then:
        def e = thrown(HttpClientResponseException)
        def response = e.response
        response.status() == HttpStatus.BAD_REQUEST
        response.body().toString().contains("Error decoding JSON stream for type")

    }


    void "test JSON POJO body with no @Body binds to arguments"() {
        given:
        def json = '{"name":"bar","age":30}'
        def request = HttpRequest.POST("/parameters/jsonBodySpread", json)
        request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
        def response = client.toBlocking().exchange(request, String)

        expect:
        response.status() == HttpStatus.OK
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.body() == json
    }

    void "full Micronaut request and response"() {
        given:
        def json = '{"name":"bar","age":30}'
        def request = HttpRequest.POST("/parameters/fullRequest", json)
        request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
        def response = client.toBlocking().exchange(request, String)

        expect:
        response.status() == HttpStatus.OK
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.body() == json
        response.header("Foo") == "Bar"
    }


    void "full Micronaut request and response - invalid JSON"() {
        when:
        def json = '{"name":"bar","age":30'
        def request = HttpRequest.POST("/parameters/fullRequest", json)
        request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
        client.toBlocking().exchange(request, Argument.STRING, Argument.STRING)

        then:
        def e = thrown(HttpClientResponseException)
        def response = e.response
        response.status() == HttpStatus.BAD_REQUEST
        response.body().toString().contains("Error decoding JSON stream for type")
    }

    void "test multipart binding"() {

        given:
        def builder = MultipartBody.builder()
        builder.addPart("foo", "bar")
        builder.addPart(
                "one",
                "one.json",
                MediaType.APPLICATION_JSON_TYPE,
                '{"name":"bar","age":20}'.bytes
        )
        builder.addPart(
                "two",
                "two.txt",
                MediaType.TEXT_PLAIN_TYPE,
                'Whatever'.bytes
        )
        builder.addPart(
                "three",
                "some.doc",
                MediaType.APPLICATION_OCTET_STREAM_TYPE,
                'My Doc'.bytes
        )
        builder.addPart(
                "four",
                "raw.doc",
                MediaType.APPLICATION_OCTET_STREAM_TYPE,
                'Another Doc'.bytes
        )
        def request = HttpRequest.POST("/parameters/multipart", builder.build())
        request.contentType(MediaType.MULTIPART_FORM_DATA)
        def response = client.toBlocking().exchange(request, Argument.STRING, Argument.STRING)

        expect:
        response.status() == HttpStatus.OK
        response.body() == 'Good: true'

    }
}
