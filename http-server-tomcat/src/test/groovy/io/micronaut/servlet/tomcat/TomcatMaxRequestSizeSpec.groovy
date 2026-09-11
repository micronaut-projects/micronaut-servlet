package io.micronaut.servlet.tomcat

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Consumes
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * micronaut.server.max-request-size applies to every request body, as on the Netty server, not only to multipart
 * uploads: a body that declares too large a length is refused before the route runs, and one that declares no
 * length is cut off with the same status once it grows past the limit.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'TomcatMaxRequestSizeSpec')
@Property(name = 'micronaut.server.max-request-size', value = '1024')
class TomcatMaxRequestSizeSpec extends Specification {

    @Inject
    EmbeddedServer embeddedServer

    HttpClient client = HttpClient.newHttpClient()

    void "a body within the limit is accepted"() {
        when:
        HttpResponse<String> response = send('x' * 512, true)

        then:
        response.statusCode() == 200
        response.body() == '512'
    }

    void "a body declaring a length over the limit is refused with 413 and never reaches the route"() {
        given:
        SizeController controller = embeddedServer.applicationContext.getBean(SizeController)
        controller.reached = false

        when:
        HttpResponse<String> response = send('x' * 4096, true)

        then:
        response.statusCode() == 413
        !controller.reached
    }

    void "a chunked body that grows past the limit is refused with 413"() {
        when:
        HttpResponse<String> response = send('x' * 4096, false)

        then:
        response.statusCode() == 413
    }

    void "an oversized form submission is refused with 413 even though the container parses forms itself"() {
        given:
        SizeController controller = embeddedServer.applicationContext.getBean(SizeController)
        controller.reached = false
        String form = 'name=' + ('x' * 4096)

        when:
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(embeddedServer.URI.resolve('/size/form'))
            .header('Content-Type', MediaType.APPLICATION_FORM_URLENCODED)
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build(), HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 413
        !controller.reached
    }

    void "an oversized JSON body is refused with 413"() {
        given:
        String json = '{"value":"' + ('x' * 4096) + '"}'

        when:
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(embeddedServer.URI.resolve('/size/json'))
            .header('Content-Type', MediaType.APPLICATION_JSON)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(), HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 413
    }

    private HttpResponse<String> send(String body, boolean declareLength) {
        HttpRequest.BodyPublisher publisher = declareLength
            ? HttpRequest.BodyPublishers.ofString(body)
            : HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(body))
        HttpRequest request = HttpRequest.newBuilder(embeddedServer.URI.resolve('/size'))
            .header('Content-Type', MediaType.TEXT_PLAIN)
            .POST(publisher)
            .build()
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Requires(property = 'spec.name', value = 'TomcatMaxRequestSizeSpec')
    @Controller('/size')
    static class SizeController {
        volatile boolean reached

        @Post
        @Consumes(MediaType.TEXT_PLAIN)
        String size(@Body String body) {
            reached = true
            String.valueOf(body.length())
        }

        @Post('/json')
        String json(@Body Map<String, String> body) {
            reached = true
            String.valueOf(body.get('value').length())
        }

        @Post('/form')
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        String form(String name) {
            reached = true
            String.valueOf(name.length())
        }
    }
}
