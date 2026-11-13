package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * Percent-encoded request targets, sent over a raw socket so that no client normalises them first.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'JettyRequestUriSpec')
class JettyRequestUriSpec extends Specification {

    @Inject
    EmbeddedServer embeddedServer

    // Jetty itself rejects an encoded '%' in the path as ambiguous, so that case is not part of this table
    void "the request target #target is decoded exactly once"() {
        when:
        String response = rawRequest(target)

        then:
        response.startsWith("HTTP/1.1 ${status}")
        body == null || response.endsWith(body)

        where:
        target                       | status | body
        '/probe/echo/hello%20world'  | 200    | 'echo=hello world'
        '/probe/echo/x%23frag'       | 200    | 'echo=x#frag'
        '/probe/path%3Fx=1'          | 404    | null
    }

    void "a malformed query string is a 400 without the container's error details"() {
        when:
        String response = rawRequest('/probe/path?a=%ZZ')

        then:
        response.startsWith('HTTP/1.1 400')
        !response.contains('Exception')
        !response.contains('Apache Tomcat')
    }

    private String rawRequest(String target) {
        try (Socket socket = new Socket(embeddedServer.host, embeddedServer.port)) {
            socket.soTimeout = 5000
            socket.outputStream.write("GET ${target} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1))
            socket.outputStream.flush()
            return new String(socket.inputStream.readAllBytes(), StandardCharsets.UTF_8)
        }
    }

    @Requires(property = 'spec.name', value = 'JettyRequestUriSpec')
    @Controller('/probe')
    static class ProbeController {

        @Get(value = '/echo/{name}', produces = MediaType.TEXT_PLAIN)
        String echo(String name) {
            "echo=$name"
        }

        @Get(value = '/path', produces = MediaType.TEXT_PLAIN)
        String path() {
            'path'
        }
    }
}
