package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Introspected
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import reactor.core.publisher.Mono
import spock.lang.Specification

/**
 * A body bound as a {@link Mono} is buffered and then read as the declared element type: raw bytes, a JSON
 * object, or one property of the JSON object when the binding names it.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'JettyReactiveBodyBindingSpec')
class JettyReactiveBodyBindingSpec extends Specification {

    @Inject
    @Client('/')
    HttpClient client

    void "a Mono of bytes receives the raw body"() {
        expect:
        client.toBlocking().retrieve(HttpRequest.POST('/reactive-body/bytes', 'abc'.bytes)
                .contentType(MediaType.APPLICATION_OCTET_STREAM_TYPE), String) == 'bytes:3'
    }

    void "a Mono of an object receives the decoded JSON body"() {
        expect:
        client.toBlocking().retrieve(HttpRequest.POST('/reactive-body/pojo', '{"name":"Fred","age":10}'), String) == 'Fred is 10'
    }

    void "a named Mono receives one property of the JSON body"() {
        expect:
        client.toBlocking().retrieve(HttpRequest.POST('/reactive-body/named', '{"name":"Fred","age":10}'), String) == 'name:Fred'
    }

    void "a named Mono of a property the JSON body lacks is rejected, as on Netty"() {
        when:
        client.toBlocking().retrieve(HttpRequest.POST('/reactive-body/named', '{"age":10}'), String)

        then:
        HttpClientResponseException e = thrown()
        e.status == HttpStatus.BAD_REQUEST
        e.response.getBody(String).orElse('').contains('name')
    }

    @Requires(property = 'spec.name', value = 'JettyReactiveBodyBindingSpec')
    @Controller('/reactive-body')
    static class ReactiveBodyController {

        @Post(value = '/bytes', consumes = MediaType.APPLICATION_OCTET_STREAM, produces = MediaType.TEXT_PLAIN)
        Mono<String> bytes(@Body Mono<byte[]> body) {
            body.map { "bytes:${it.length}".toString() }
        }

        @Post(value = '/pojo', produces = MediaType.TEXT_PLAIN)
        Mono<String> pojo(@Body Mono<Person> body) {
            body.map { "${it.name} is ${it.age}".toString() }
        }

        @Post(value = '/named', produces = MediaType.TEXT_PLAIN)
        Mono<String> named(@Body('name') Mono<String> name) {
            name.map { "name:$it".toString() }.defaultIfEmpty('name:none')
        }
    }

    @Introspected
    static class Person {
        String name
        int age
    }
}
