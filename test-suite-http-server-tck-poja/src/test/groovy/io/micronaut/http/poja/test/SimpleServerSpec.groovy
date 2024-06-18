package io.micronaut.http.poja.test


import io.micronaut.context.annotation.Property
import io.micronaut.core.annotation.NonNull
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest

@MicronautTest
@Property(name = "micronaut.security.enabled", value = "false")
class SimpleServerSpec extends BaseServerlessApplicationSpec {


    void "test GET method"() {
        given:
        BlockingHttpClient client = HttpClient.create(new URL("http://localhost:" + app.port)).toBlocking()

        when:
        HttpResponse<?> response = client.exchange(HttpRequest.GET("/test").header("Host", "h"))

        then:
        response.status == HttpStatus.OK
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.getBody(String.class).get() == 'Hello, Micronaut Without Netty!\n'
    }

    void "test invalid GET method"() {
        given:
        BlockingHttpClient client = HttpClient.create(new URL("http://localhost:" + app.port)).toBlocking()

        when:
        HttpResponse<?> response = client.exchange(HttpRequest.GET("/test-invalid").header("Host", "h"))

        then:
        var e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
        e.response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        e.response.getBody(String.class).get().length() > 0
    }

    void "test DELETE method"() {
        given:
        BlockingHttpClient client = HttpClient.create(new URL("http://localhost:" + app.port)).toBlocking()

        when:
        HttpResponse<?> response = client.exchange(HttpRequest.DELETE("/test").header("Host", "h"))

        then:
        response.status() == HttpStatus.OK
        response.getBody(String.class).isEmpty()
    }

    void "test POST method"() {
        given:
        BlockingHttpClient client = HttpClient.create(new URL("http://localhost:" + app.port)).toBlocking()

        when:
        HttpResponse<?> response = client.exchange(HttpRequest.POST("/test/Andriy", null).header("Host", "h"))

        then:
        response.status() == HttpStatus.CREATED
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.getBody(String.class).get() == "Hello, Andriy\n"
    }

    void "test PUT method"() {
        given:
        BlockingHttpClient client = HttpClient.create(new URL("http://localhost:" + app.port)).toBlocking()

        when:
        HttpResponse<?> response = client.exchange(HttpRequest.PUT("/test/Andriy", null).header("Host", "h"))

        then:
        response.status() == HttpStatus.OK
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.getBody(String.class).get() == "Hello, Andriy!\n"
    }

    /**
     * A controller for testing.
     */
    @Controller(value = "/test", produces = MediaType.TEXT_PLAIN, consumes = MediaType.ALL)
    static class TestController {

        @Get
        String index() {
            return "Hello, Micronaut Without Netty!\n"
        }

        @Delete
        void delete() {
            System.err.println("Delete called")
        }

        @Post("/{name}")
        @Status(HttpStatus.CREATED)
        String create(@NonNull String name) {
            return "Hello, " + name + "\n"
        }

        @Put("/{name}")
        @Status(HttpStatus.OK)
        String update(@NonNull String name) {
            return "Hello, " + name + "!\n"
        }

    }

}
