package io.micronaut.http.poja.sample

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.poja.sample.model.Cactus
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.spock.annotation.MicronautTest;
import jakarta.inject.Inject;
import spock.lang.Specification;

@MicronautTest
class SimpleServerSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    void "test GET method"() {
        when:
        HttpResponse<?> response = client.toBlocking().exchange(HttpRequest.GET("/").header("Host", "h"))

        then:
        response.status == HttpStatus.OK
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.getBody(String.class).get() == 'Hello, Micronaut Without Netty!\n'
    }

    void "test invalid GET method"() {
        when:
        HttpResponse<?> response = client.toBlocking().exchange(HttpRequest.GET("/test/invalid").header("Host", "h"))

        then:
        var e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
        e.response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        e.response.getBody(String.class).get().length() > 0
    }

    void "test DELETE method"() {
        when:
        HttpResponse<?> response = client.toBlocking().exchange(HttpRequest.DELETE("/").header("Host", "h"))

        then:
        response.status() == HttpStatus.OK
        response.getBody(String.class).isEmpty()
    }

    void "test POST method"() {
        when:
        HttpResponse<?> response = client.toBlocking().exchange(HttpRequest.POST("/Andriy", null).header("Host", "h"))

        then:
        response.status() == HttpStatus.CREATED
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.getBody(String.class).get() == "Hello, Andriy\n"
    }

    void "test PUT method"() {
        when:
        HttpResponse<?> response = client.toBlocking().exchange(HttpRequest.PUT("/Andriy", null).header("Host", "h"))

        then:
        response.status() == HttpStatus.OK
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.getBody(String.class).get() == "Hello, Andriy!\n"
    }

    void "test GET method with serialization"() {
        when:
        HttpResponse<?> response = client.toBlocking().exchange(HttpRequest.GET("/cactus").header("Host", "h"))

        then:
        response.status == HttpStatus.OK
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.getBody(Cactus.class).get() == new Cactus("green", 1)
    }

}
