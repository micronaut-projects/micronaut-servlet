package io.micronaut.http.poja


import io.micronaut.core.annotation.NonNull
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import spock.lang.Specification
import spock.lang.Stepwise

@Stepwise
@MicronautTest
class SimpleServerSpec extends Specification {

    @Inject
    StringTestingClient client

    void "test GET method"() {
        when:
        var response = client.exchange(unindent("""
        GET /test HTTP/1.1\r
        Host: h\r
        \r
        """))

        then:
        response == unindent("""
        HTTP/1.1 200 Ok\r
        Content-Length: 32\r
        Content-Type: text/plain\r
        \r
        Hello, Micronaut Without Netty!
        """)
    }

    void "test invalid GET method"() {
        when:
        var response = client.exchange(unindent("""
        GET /invalid-test HTTP/1.1\r
        Host: h\r
        \r
        """))

        then:
        response == unindent("""\
        HTTP/1.1 404 Not Found\r
        Content-Length: 140\r
        Content-Type: application/json\r
        \r
        {"_links":{"self":[{"href":"/invalid-test","templated":false}]},"_embedded":{"errors":[{"message":"Page Not Found"}]},"message":"Not Found"}""")
    }

    void "test DELETE method"() {
        when:
        var response = client.exchange(unindent("""
        DELETE /test HTTP/1.1\r
        Host: h\r
        \r
        """))

        then:
        response == unindent("""
        HTTP/1.1 200 Ok\r
        Content-Length: 0\r
        \r
        """)
    }

    void "test POST method"() {
        when:
        var response = client.exchange(unindent("""
        POST /test/Dream HTTP/1.1\r
        Host: h\r
        \r
        """))

        then:
        response == unindent("""
        HTTP/1.1 201 Created\r
        Content-Length: 13\r
        Content-Type: text/plain\r
        \r
        Hello, Dream
        """)
    }

    void "test PUT method"() {
        when:
        var response = client.exchange(unindent("""
        PUT /test/Dream1 HTTP/1.1\r
        Host: h\r
        \r
        """))

        then:
        response == unindent("""
        HTTP/1.1 200 Ok\r
        Content-Length: 15\r
        Content-Type: text/plain\r
        \r
        Hello, Dream1!
        """)
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

    static String unindent(String value, int indentSpaces = 8) {
        while (value.charAt(0) == '\n' as char) {
            value = value.substring(1)
        }
        var lines = value.split("\n")
            .collect({ it.startsWith(" ".repeat(indentSpaces)) ? it.substring(indentSpaces) : it })
            .join("\n")
    }

    @Singleton
    static class StringTestingClient {

        private EmbeddedServer server

        StringTestingClient(EmbeddedServer server) {
            this.server = server
        }

        String exchange(String request) {
            try (Socket socket = new Socket(server.host, server.port)) {
                OutputStream output = socket.getOutputStream()
                output.write(request.getBytes())

                InputStream input = socket.getInputStream()
                return new String(input.readAllBytes())
                        .replaceAll("Date:[^\r]+\r\n", "")
            } catch (IOException e) {
                throw new RuntimeException("Could not exchange request with server", e)
            }
        }

    }

}
