
package io.micronaut.servlet.jetty

import groovy.json.JsonSlurper
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Introspected
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
import io.micronaut.http.codec.CodecException
import io.micronaut.http.hateoas.JsonError
import io.micronaut.http.hateoas.Link
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.PendingFeature
import spock.lang.Specification

@MicronautTest
@Property(name = 'spec.name', value = 'JettyJsonBodyBindingSpec2')
class JettyJsonBodyBindingSpec2 extends Specification {

    @Inject
    @Client("/")
    HttpClient rxClient

    @PendingFeature
    void "test map-based body parsing with invalid JSON"() {
        when:
        rxClient.toBlocking().exchange(HttpRequest.POST('/json/map', '{"title":The Stand}'), String)

        then:
        def e = thrown(HttpClientResponseException)
        e.response.getBody(Map).get().message.contains """Unable to decode request body: Error decoding JSON stream for type [json]: Unrecognized token 'The'"""
        e.response.status == HttpStatus.BAD_REQUEST

        when:
        def response = e.response
        def body = e.response.getBody(String).orElse(null)
        def result = new JsonSlurper().parseText(body)

        then:
        response.code() == HttpStatus.BAD_REQUEST.code
        response.headers.get(HttpHeaders.CONTENT_TYPE) == io.micronaut.http.MediaType.APPLICATION_JSON
        result['_links'].self.href == ['/json/map']
        result.message.startsWith "Invalid JSON"
        result.message.contains "Unrecognized token 'The'"
    }

    @Requires(property = 'spec.name', value = 'JettyJsonBodyBindingSpec2')
    @Controller(value = "/json", produces = io.micronaut.http.MediaType.APPLICATION_JSON)
    static class JsonController {

        @Post("/map")
        String map(@Body Map<String, Object> json) {
            "Body: ${json}"
        }

        @Error(CodecException)
        HttpResponse jsonError(HttpRequest request, CodecException jsonParseException) {
            def response = HttpResponse.status(HttpStatus.BAD_REQUEST, "No!! Invalid JSON")
            def error = new JsonError("Invalid JSON: ${jsonParseException.message}")
            error.link(Link.SELF, Link.of(request.getUri()))
            response.body(error)
            return response
        }
    }

    @Introspected
    static class Foo {
        String name
        Integer age

        @Override
        String toString() {
            "Foo($name, $age)"
        }
    }
}
