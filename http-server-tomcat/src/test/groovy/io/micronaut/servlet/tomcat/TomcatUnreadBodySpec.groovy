package io.micronaut.servlet.tomcat

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Consumes
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Status
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.servlet.engine.DefaultServletHttpRequest
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * The body a route did not read is drained once the response is sent, within a time bound.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'TomcatUnreadBodySpec')
class TomcatUnreadBodySpec extends Specification {

    @Inject
    EmbeddedServer embeddedServer

    long previousTimeout

    void setup() {
        previousTimeout = DefaultServletHttpRequest.drainTimeoutMillis
        DefaultServletHttpRequest.drainTimeoutMillis = 1000
    }

    void cleanup() {
        DefaultServletHttpRequest.drainTimeoutMillis = previousTimeout
    }

    void "the response is sent before the unread body arrives, and a client that stalls has its connection closed"() {
        given:
        Socket socket = new Socket('127.0.0.1', embeddedServer.port)
        socket.soTimeout = 10_000

        when: "a client sends only part of the body"
        socket.outputStream.write(("POST /unread HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nContent-Length: 100\r\n\r\n" + ('x' * 10)).getBytes(StandardCharsets.ISO_8859_1))
        socket.outputStream.flush()
        long start = System.nanoTime()
        String statusLine = readLine(socket.inputStream)

        then: "the response does not wait for the rest of the body"
        statusLine.startsWith('HTTP/1.1 204')

        when: "the rest never comes"
        while (socket.inputStream.read() != -1) {
            // the headers of the response
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000

        then: "the connection is closed once the drain timed out"
        elapsedMillis < 9_000

        cleanup:
        socket.close()
    }

    void "a body the route did not read does not break the next request on the connection"() {
        given:
        Socket socket = new Socket('127.0.0.1', embeddedServer.port)
        socket.soTimeout = 10_000
        String request = "POST /unread HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nContent-Length: 100\r\n\r\n" + ('x' * 100)

        when:
        socket.outputStream.write((request + request).getBytes(StandardCharsets.ISO_8859_1))
        socket.outputStream.flush()
        String first = readLine(socket.inputStream)
        skipHeaders(socket.inputStream)
        String second = readLine(socket.inputStream)

        then:
        first.startsWith('HTTP/1.1 204')
        second.startsWith('HTTP/1.1 204')

        cleanup:
        socket.close()
    }

    private static String readLine(InputStream input) {
        StringBuilder line = new StringBuilder()
        int c
        while ((c = input.read()) != -1 && c != '\n') {
            if (c != '\r') {
                line.append((char) c)
            }
        }
        line.toString()
    }

    private static void skipHeaders(InputStream input) {
        while (!readLine(input).isEmpty()) {
            // a header
        }
    }

    @Requires(property = 'spec.name', value = 'TomcatUnreadBodySpec')
    @Controller('/unread')
    static class UnreadController {
        @Post
        @Consumes(MediaType.TEXT_PLAIN)
        @Status(HttpStatus.NO_CONTENT)
        void ignore() {
        }
    }
}
