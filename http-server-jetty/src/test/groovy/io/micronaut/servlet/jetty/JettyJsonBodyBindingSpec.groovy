
package io.micronaut.servlet.jetty

import com.fasterxml.jackson.core.JsonParseException
import groovy.json.JsonSlurper
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Error
import io.micronaut.http.annotation.Post
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.hateoas.JsonError
import io.micronaut.http.hateoas.Link
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

@MicronautTest
@Property(name = 'spec.name', value = 'JettyJsonBodyBindingSpec')
class JettyJsonBodyBindingSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient rxClient

    void "test JSON is not parsed when the body is a raw body type"() {
        when:
        def json = '{"title":"The Stand"'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/string', json), String
        )

        then:
        response.code() == HttpStatus.OK.code
        response.body() == 'Body: {"title":"The Stand"'
    }

    void "test JSON is not parsed when the body is a raw body type in a request argument"() {
        when:
        def json = '{"title":"The Stand"'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/request-string', json), String
        )

        then:
        response.code() == HttpStatus.OK.code
        response.body() == 'Body: {"title":"The Stand"'
    }

    void "test parse body into parameters if no @Body specified"() {
        when:
        def json = '{"name":"Fred", "age":10}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/params', json), String
        )

        then:
        response.code() == HttpStatus.OK.code
        response.body() == "Body: Foo(Fred, 10)"
    }

    void "test map-based body parsing with invalid JSON"() {

        when:
        def json = '{"title":The Stand}'
        rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/map', json), String
        )

        then:
        def e = thrown(HttpClientResponseException)
        e.message == """Unable to decode request body: Error decoding JSON stream for type [json]: Unrecognized token 'The': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')
 at [Source: (org.eclipse.jetty.server.HttpInput); line: 1, column: 14]"""
        e.response.status == HttpStatus.BAD_REQUEST

        when:
        def response = e.response
        def body = e.response.getBody(String).orElse(null)
        def result = new JsonSlurper().parseText(body)

        then:
        response.code() == HttpStatus.BAD_REQUEST.code
        response.headers.get(HttpHeaders.CONTENT_TYPE) == io.micronaut.http.MediaType.APPLICATION_JSON
        result['_links'].self.href == '/json/map'
//        result.message.startsWith('Invalid JSON')
    }

    void "test simple map body parsing"() {
        when:
        def json = '{"title":"The Stand"}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/map', json), String
        )

        then:
        response.body() == "Body: [title:The Stand]"
    }

    void "test simple string-based body parsing"() {
        when:
        def json = '{"title":"The Stand"}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/string', json), String
        )

        then:
        response.body() == "Body: $json"
    }

    void "test binding to part of body with @Body(name)"() {
        when:
        def json = '{"title":"The Stand"}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/body-title', json), String
        )

        then:
        response.body() == "Body Title: The Stand"
    }

    void  "test simple string-based body parsing with request argument"() {
        when:
        def json = '{"title":"The Stand"}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/request-string', json), String
        )

        then:
        response.body() == "Body: $json"
    }

    void "test simple string-based body parsing with invalid mime type"() {
        when:
        def json = '{"title":"The Stand"}'
        rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/map', json).contentType(io.micronaut.http.MediaType.APPLICATION_ATOM_XML_TYPE), String
        )

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNSUPPORTED_MEDIA_TYPE
    }

    void "test simple POGO body parsing"() {
        when:
        def json = '{"name":"Fred", "age":10}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/object', json), String
        )

        then:
        response.body() == "Body: Foo(Fred, 10)"
    }

    void "test simple POGO body parse and return"() {
        when:
        def json = '{"name":"Fred","age":10}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/object-to-object', json), String
        )

        then:
        response.body() == json
    }

    void "test array POGO body parsing"() {
        when:
        def json = '[{"name":"Fred", "age":10},{"name":"Barney", "age":11}]'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/array', json), String
        )

        then:
        response.body() == "Body: Foo(Fred, 10),Foo(Barney, 11)"
    }

    void "test array POGO body parsing and return"() {
        when:
        def json = '[{"name":"Fred","age":10},{"name":"Barney","age":11}]'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/array-to-array', json), String
        )

        then:
        response.body() == json
    }

    void "test list POGO body parsing"() {
        when:
        def json = '[{"name":"Fred", "age":10},{"name":"Barney", "age":11}]'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/list', json), String
        )

        then:
        response.body() == "Body: Foo(Fred, 10),Foo(Barney, 11)"
    }

    void "test future argument handling with string"() {
        when:
        def json = '{"name":"Fred","age":10}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/future', json), String
        )

        then:
        response.body() == "Body: $json".toString()
    }

    void "test future argument handling with map"() {
        when:
        def json = '{"name":"Fred","age":10}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/future-map', json), String
        )

        then:
        response.body() == "Body: [name:Fred, age:10]".toString()
    }

    void "test future argument handling with POGO"() {
        when:
        def json = '{"name":"Fred","age":10}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/future-object', json), String
        )

        then:
        response.body() == "Body: Foo(Fred, 10)".toString()
    }

    void "test publisher argument handling with POGO"() {
        when:
        def json = '{"name":"Fred","age":10}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/publisher-object', json), String
        )

        then:
        response.body() == "[Foo(Fred, 10)]".toString()
    }

    void "test singe argument handling"() {
        when:
        def json = '{"message":"foo"}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/single', json), String
        )

        then:
        response.body() == "$json".toString()
    }

    void "test request generic type binding"() {
        when:
        def json = '{"name":"Fred","age":10}'
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/request-generic', json), String
        )

        then:
        response.body() == "Foo(Fred, 10)".toString()
    }

    void "test request generic type no body"() {
        when:
        def json = ''
        def response = rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/request-generic', json), String
        )

        then:
        response.code() == HttpStatus.OK.code
        response.body() == 'not found'
    }

    void "test request generic type conversion error"() {
        when:
        def json = '[1,2,3]'
        rxClient.toBlocking().exchange(
                HttpRequest.POST('/json/request-generic', json), String
        )

        then:
        def e = thrown(HttpClientResponseException)
        def response = e.response
        response.status() == HttpStatus.BAD_REQUEST
        response.body().toString().contains("Error decoding JSON stream for type")
    }

    @Requires(property = 'spec.name', value = 'JettyJsonBodyBindingSpec')
    @Controller(value = "/json", produces = io.micronaut.http.MediaType.APPLICATION_JSON)
    static class JsonController {

        @Post("/params")
        String params(String name, int age) {
            "Body: ${new Foo(name: name, age: age)}"
        }

        @Post("/string")
        String string(@Body String text) {
            "Body: ${text}"
        }

        @Post("/body-title")
        String bodyNamed(@Body("title") String text) {
            "Body Title: ${text}"
        }

        @Post("/request-string")
        String requestString(HttpRequest<String> req) {
            "Body: ${req.body.orElse("empty")}"
        }

        @Post("/map")
        String map(@Body Map<String, Object> json) {
            "Body: ${json}"
        }

        @Post("/object")
        String object(@Body Foo foo) {
            "Body: $foo"
        }

        @Post("/object-to-object")
        Foo objectToObject(@Body Foo foo) {
            return foo
        }

        @Post("/array") array(@Body Foo[] foos) {
            "Body: ${foos.join(',')}"
        }

        @Post("/array-to-array") arrayToArray(@Body Foo[] foos) {
            return foos
        }

        @Post("/list") list(@Body List<Foo> foos) {
            "Body: ${foos.join(',')}"
        }

        @Post("/nested")
        String nested(@Body('foo') Foo foo) {
            "Body: $foo"
        }

        @Post("/single")
        Mono<String> single(@Body Mono<String> message) {
            message

        }

        @Post("/future")
        CompletableFuture<String> future(@Body CompletableFuture<String> future) {
            future.thenApply({ String json ->
                "Body: $json".toString()
            })
        }

        @Post("/future-map")
        CompletableFuture<String> futureMap(@Body CompletableFuture<Map<String,Object>> future) {
            future.thenApply({ Map<String,Object> json ->
                "Body: $json".toString()
            })
        }


        @Post("/future-object")
        CompletableFuture<String> futureObject(@Body CompletableFuture<Foo> future) {
            future.thenApply({ Foo foo ->
                "Body: $foo".toString()
            })
        }

        @Post("/publisher-object")
        Publisher<String> publisherObject(@Body Flux<Foo> publisher) {
            return publisher
                    .subscribeOn(Schedulers.boundedElastic())
                    .map({ Foo foo ->
                        foo.toString()
                    })
        }

        @Post("/request-generic")
        String requestGeneric(HttpRequest<Foo> request) {
            return request.getBody().map({ foo -> foo.toString()}).orElse("not found")
        }

        @Error(JsonParseException)
        HttpResponse jsonError(HttpRequest request, JsonParseException jsonParseException) {
            def response = HttpResponse.status(HttpStatus.BAD_REQUEST, "No!! Invalid JSON")
            def error = new JsonError("Invalid JSON: ${jsonParseException.message}")
            error.link(Link.SELF, Link.of(request.getUri()))
            response.body(error)
            return response
        }
    }

    static class Foo {
        String name
        Integer age

        @Override
        String toString() {
            "Foo($name, $age)"
        }
    }
}
