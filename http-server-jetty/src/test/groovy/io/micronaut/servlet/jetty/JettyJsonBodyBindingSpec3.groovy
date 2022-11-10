
package io.micronaut.servlet.jetty


import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Introspected
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
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

import static io.micronaut.http.MediaType.APPLICATION_JSON

@MicronautTest
@Property(name = 'spec.name', value = 'JettyJsonBodyBindingSpec3')
class JettyJsonBodyBindingSpec3 extends Specification {

    @Inject
    @Client("/")
    HttpClient rxClient

    void "test map-based body parsing with invalid JSON"() {
        when:
        rxClient.toBlocking().exchange(HttpRequest.POST('/json/map', '{"title":The Stand}'), String)

        then:
        def e = thrown(HttpClientResponseException)
        e.response.status == HttpStatus.BAD_REQUEST
        e.response.getBody().isEmpty()
    }

    @Requires(property = 'spec.name', value = 'JettyJsonBodyBindingSpec3')
    @Controller(value = "/json", produces = APPLICATION_JSON)
    static class JsonController {

        @Post("/map")
        String map(@Body Map<String, Object> json) {
            "Body: ${json}"
        }

        @Error(CodecException)
        HttpResponse jsonError(HttpRequest request, CodecException jsonParseException) {
            return HttpResponse.status(HttpStatus.BAD_REQUEST, "No!! Invalid JSON")
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
