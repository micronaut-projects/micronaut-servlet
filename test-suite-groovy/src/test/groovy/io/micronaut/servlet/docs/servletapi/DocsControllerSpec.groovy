package io.micronaut.servlet.docs.servletapi

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * Exercises the controller shown in the Servlet API section of the guide, so the documented
 * sources are real, compiled and covered.
 */
@Property(name = "spec.name", value = "DocsControllerTest")
@MicronautTest
class DocsControllerSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    void "the servlet request and response can be injected"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/docs/hello?name=Fred"), String)

        then:
        response.status() == HttpStatus.ACCEPTED
        response.body() == "Hello Fred"
    }

    void "readable and writable simplify io"() {
        expect:
        client.toBlocking().retrieve(HttpRequest.POST("/docs/writable", "Fred").contentType(MediaType.TEXT_PLAIN), String) == "Hello Fred"
    }

    void "parts can be injected with @Part"() {
        given:
        MultipartBody body = MultipartBody.builder()
                .addPart("attribute", "value")
                .addPart("one", "one.json", MediaType.APPLICATION_JSON_TYPE, '{"name":"Fred","age":20}'.getBytes(StandardCharsets.UTF_8))
                .addPart("two", "two.txt", MediaType.TEXT_PLAIN_TYPE, "text".getBytes(StandardCharsets.UTF_8))
                .addPart("three", "three.bin", MediaType.APPLICATION_OCTET_STREAM_TYPE, [1, 2, 3] as byte[])
                .addPart("four", "four.txt", MediaType.TEXT_PLAIN_TYPE, "raw".getBytes(StandardCharsets.UTF_8))
                .addPart("five", "five.txt", MediaType.TEXT_PLAIN_TYPE, "part".getBytes(StandardCharsets.UTF_8))
                .build()

        expect:
        client.toBlocking().retrieve(HttpRequest.POST("/docs/multipart", body).contentType(MediaType.MULTIPART_FORM_DATA_TYPE), String) == "Ok"
    }
}
