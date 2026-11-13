package io.micronaut.http.poja

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import reactor.core.publisher.Flux
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.charset.StandardCharsets

/**
 * Request framing on the POJA Apache runtime: a body is consumed whether or not the application reads it, the
 * framing headers are validated as RFC 9112 section 6.3 requires, and lines are bounded, so that one request can
 * never be parsed as the start of another.
 */
@MicronautTest
@Property(name = "spec.name", value = "RequestFramingSpec")
@Property(name = "micronaut.server.max-request-size", value = "1024")
@Property(name = "poja.apache.max-line-length", value = "1024")
@Property(name = "poja.apache.max-header-count", value = "20")
@Property(name = "poja.apache.discard-timeout", value = "500ms")
class RequestFramingSpec extends Specification {

    @Inject
    EmbeddedServer server

    void "a body declared on a GET is not parsed as the next request"() {
        given:
        String smuggled = "GET /framing/admin HTTP/1.1\r\nHost: h\r\n\r\n"

        when:
        String response = exchange(
            "GET /framing/ignore HTTP/1.1\r\nHost: h\r\nContent-Length: ${smuggled.length()}\r\n\r\n" + smuggled +
                "GET /framing/ignore HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 200", "HTTP/1.1 200"]
        !response.contains("admin")
    }

    void "an unread POST body is not parsed as the next request"() {
        given:
        String smuggled = "GET /framing/admin HTTP/1.1\r\nHost: h\r\n\r\n"

        when:
        String response = exchange(
            "POST /framing/ignore HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\nContent-Length: ${smuggled.length()}\r\n\r\n" + smuggled +
                "GET /framing/ignore HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 200", "HTTP/1.1 200"]
        !response.contains("admin")
    }

    void "an unread chunked body is consumed"() {
        given:
        String smuggled = "GET /framing/admin HTTP/1.1\r\nHost: h\r\n\r\n"

        when:
        String response = exchange(
            "POST /framing/ignore HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n" +
                Integer.toHexString(smuggled.length()) + "\r\n" + smuggled + "\r\n0\r\n\r\n" +
                "GET /framing/ignore HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 200", "HTTP/1.1 200"]
        !response.contains("admin")
    }

    void "a chunked body is read and the next request served"() {
        when:
        String response = exchange(
            "POST /framing/echo HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\ntransfer-encoding: Chunked\r\n\r\n" +
                "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n" +
                "GET /framing/ignore HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 200", "HTTP/1.1 200"]
        response.contains("echo=hello world")
    }

    @Unroll
    void "ambiguous framing is rejected and the connection closed: #description"() {
        given:
        String smuggled = "GET /framing/admin HTTP/1.1\r\nHost: h\r\n\r\n"

        when:
        String response = exchange(
            "POST /framing/ignore HTTP/1.1\r\nHost: h\r\n" + headers + "\r\n" + body + smuggled +
                "GET /framing/ignore HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 400"]
        response.contains("Connection: close")
        !response.contains("admin")

        where:
        description                           | headers                                                         | body
        "Content-Length and Transfer-Encoding" | "Content-Length: 5\r\nTransfer-Encoding: chunked\r\n"         | "0\r\n\r\n"
        "Content-Length 0 and chunked"        | "Content-Length: 0\r\nTransfer-Encoding: chunked\r\n"           | "0\r\n\r\n"
        "chunked not the only coding"         | "Transfer-Encoding: gzip, chunked\r\n"                          | "0\r\n\r\n"
        "unknown coding"                      | "Transfer-Encoding: identity\r\n"                               | ""
        "non-numeric Content-Length"          | "Content-Length: abc\r\n"                                       | ""
        "negative Content-Length"             | "Content-Length: -1\r\n"                                        | ""
        "signed Content-Length"               | "Content-Length: +5\r\n"                                        | "hello"
        "list Content-Length"                 | "Content-Length: 5, 5\r\n"                                      | "hello"
        "duplicate Content-Length"            | "Content-Length: 5\r\ncontent-length: 6\r\n"                    | "hello"
        "overflowing Content-Length"          | "Content-Length: 99999999999999999999\r\n"                      | ""
    }

    void "a body over the declared limit is rejected before it is read"() {
        when:
        String response = exchange(
            "POST /framing/echo HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\nContent-Length: 2048\r\n\r\n" + ("a" * 2048) +
                "GET /framing/admin HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 413"]
        !response.contains("admin")
    }

    void "a chunked body over the limit fails and closes the connection"() {
        given:
        String chunk = "a" * 512

        when:
        String response = exchange(
            "POST /framing/ignore HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n" +
                ("200\r\n" + chunk + "\r\n") * 4 + "0\r\n\r\n" +
                "GET /framing/admin HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 200"]
        !response.contains("admin")
    }

    void "a malformed chunk closes the connection"() {
        when:
        String response = exchange(
            "POST /framing/ignore HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "zz\r\nGET /framing/admin HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 200"]
        !response.contains("admin")
    }

    void "an overlong request line is rejected"() {
        when:
        String response = exchange("GET /framing/" + ("a" * 4096) + " HTTP/1.1\r\nHost: h\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 400"]
    }

    void "an overlong request line without a line feed is rejected"() {
        when:
        String response = exchange("GET /framing/" + ("a" * 4096))

        then:
        statusLines(response) == ["HTTP/1.1 400"]
    }

    void "an overlong header line is rejected"() {
        when:
        String response = exchange("GET /framing/ignore HTTP/1.1\r\nHost: h\r\nX-Pad: " + ("a" * 4096) + "\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 400"]
    }

    void "too many headers are rejected"() {
        when:
        String response = exchange("GET /framing/ignore HTTP/1.1\r\nHost: h\r\n" + (1..50).collect { "X-H$it: v\r\n" }.join() + "\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 400"]
    }

    @Unroll
    void "a request that cannot be parsed is answered and the server keeps serving: #target"() {
        when:
        String response = exchange("GET $target HTTP/1.1\r\nHost: h\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 400"]

        when:
        String next = exchange("GET /framing/ignore HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")

        then:
        statusLines(next) == ["HTTP/1.1 200"]

        where:
        target << ["/a|b", "/%zz", "/framing/ignore?a=%zz"]
    }

    void "an unread body is not waited for when the connection closes"() {
        when: "the body is announced but never sent"
        String response = exchange("POST /framing/ignore HTTP/1.1\r\nHost: h\r\nContent-Length: 100\r\nConnection: close\r\n\r\n", false)

        then: "the response is sent and the connection closed without reading the body"
        statusLines(response) == ["HTTP/1.1 200"]
    }

    void "header names differing in case are merged"() {
        when:
        String response = exchange("GET /framing/headers HTTP/1.1\r\nHost: h\r\nX-Test: a\r\nx-test: b\r\nConnection: close\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 200"]
        response.contains("headers=[a, b]")
    }

    void "the body of a rejected request is discarded, so the rejection is not lost to a connection reset"() {
        when: "the client only reads once the server has rejected the request and closed the connection"
        String response = exchange(
            "POST /framing/echo HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\nContent-Length: 32768\r\n\r\n" + ("a" * 32768),
            false, 500)

        then:
        statusLines(response) == ["HTTP/1.1 413"]
    }

    void "the body of a request with invalid framing is discarded before the connection is closed"() {
        when:
        String response = exchange(
            "POST /framing/ignore HTTP/1.1\r\nHost: h\r\nContent-Length: abc\r\n\r\n" + ("a" * 32768), false, 500)

        then:
        statusLines(response) == ["HTTP/1.1 400"]
    }

    void "100 Continue is sent before an expected body is read"() {
        given:
        Socket socket = new Socket(server.host, server.port)
        socket.setSoTimeout(10_000)
        OutputStream output = socket.getOutputStream()
        InputStream input = socket.getInputStream()

        when: "the client sends the headers and waits for 100 Continue before the body"
        output.write("POST /framing/echo HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\nContent-Length: 5\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n"
            .getBytes(StandardCharsets.ISO_8859_1))
        output.flush()
        String interim = readHead(input)
        output.write("hello".getBytes(StandardCharsets.ISO_8859_1))
        output.flush()
        String response = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1)

        then:
        interim == "HTTP/1.1 100 Continue\r\n\r\n"
        statusLines(response) == ["HTTP/1.1 200"]
        response.contains("echo=hello")

        cleanup:
        socket.close()
    }

    void "an expected body that was never asked for closes the connection instead of being read from the next request"() {
        when: "the client waits for 100 Continue, gets the final response instead and does not send the body"
        String response = exchange(
            "POST /framing/ignore HTTP/1.1\r\nHost: h\r\nContent-Length: 100\r\nExpect: 100-continue\r\n\r\n", false)

        then: "the connection is closed after the response rather than waiting for a body that does not come"
        statusLines(response) == ["HTTP/1.1 200"]
    }

    void "an unsupported expectation is rejected"() {
        when:
        String response = exchange(
            "POST /framing/echo HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\nContent-Length: 5\r\nExpect: something-else\r\n\r\nhello", false)

        then:
        statusLines(response) == ["HTTP/1.1 417"]
    }

    void "an unsupported expectation is rejected on a request without a body"() {
        when:
        String response = exchange("GET /framing/ignore HTTP/1.1\r\nHost: h\r\nExpect: something-else\r\n\r\n", false)

        then:
        statusLines(response) == ["HTTP/1.1 417"]
    }

    void "an expectation on an HTTP/1.0 request is ignored"() {
        when: "an HTTP/1.0 client sends the body straight away"
        String response = exchange(
            "POST /framing/echo HTTP/1.0\r\nHost: h\r\nContent-Type: text/plain\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\nhello")

        then: "no 100 Continue is sent, which an HTTP/1.0 client would take as the final response"
        statusLines(response) == ["HTTP/1.1 200"]
        response.contains("echo=hello")

        when:
        String unsupported = exchange("GET /framing/ignore HTTP/1.0\r\nHost: h\r\nExpect: something-else\r\n\r\n")

        then:
        statusLines(unsupported) == ["HTTP/1.1 200"]
    }

    void "a body the client does not finish sending is waited for within the discard timeout only"() {
        when: "the application does not read the body and the client sends only part of it, keeping the connection open"
        long start = System.nanoTime()
        String response = exchange("POST /framing/ignore HTTP/1.1\r\nHost: h\r\nContent-Length: 1000\r\n\r\n" + ("a" * 10), false)
        long elapsedMillis = (System.nanoTime() - start).intdiv(1_000_000)

        then: "the response is sent and the connection closed once the timeout has passed"
        statusLines(response) == ["HTTP/1.1 200"]
        elapsedMillis >= 400
        elapsedMillis < 5000
    }

    void "a response to an HTTP/1.0 request without a length is delimited by closing the connection, not chunked"() {
        when:
        String response = exchange("GET /framing/stream HTTP/1.0\r\nHost: h\r\n\r\n")

        then:
        statusLines(response) == ["HTTP/1.1 200"]
        !response.toLowerCase().contains("transfer-encoding")
        response.endsWith("\r\n\r\nline 0\nline 1\nline 2\n")
    }

    private static String readHead(InputStream input) {
        StringBuilder head = new StringBuilder()
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = input.read()
            if (b < 0) {
                break
            }
            head.append((char) b)
        }
        return head.toString()
    }

    private String exchange(String request, boolean halfClose = true, long readDelayMillis = 0) {
        try (Socket socket = new Socket(server.host, server.port)) {
            socket.setSoTimeout(10_000)
            OutputStream output = socket.getOutputStream()
            try {
                output.write(request.getBytes(StandardCharsets.ISO_8859_1))
                output.flush()
                if (halfClose) {
                    socket.shutdownOutput()
                }
            } catch (IOException ignored) {
                // the server may close the connection before the whole request is written
            }
            if (readDelayMillis > 0) {
                Thread.sleep(readDelayMillis)
            }
            ByteArrayOutputStream received = new ByteArrayOutputStream()
            try {
                socket.getInputStream().transferTo(received)
            } catch (SocketException ignored) {
                // connection reset after the response
            }
            return received.toString(StandardCharsets.ISO_8859_1)
        }
    }

    // the status line of every response, in order
    private static List<String> statusLines(String response) {
        return (response =~ /HTTP\/1\.1 \d{3}/).collect { it as String }
    }

    @Requires(property = "spec.name", value = "RequestFramingSpec")
    @Controller(value = "/framing", produces = MediaType.TEXT_PLAIN)
    static class FramingController {

        @Get("/stream")
        Flux<String> stream() {
            return Flux.range(0, 3).map { "line $it\n".toString() }
        }

        @Get("/ignore")
        String ignoreGet() {
            return "ignored"
        }

        @Post(value = "/ignore", consumes = MediaType.ALL)
        String ignorePost() {
            return "ignored"
        }

        @Post(value = "/echo", consumes = MediaType.TEXT_PLAIN)
        String echo(@Body String body) {
            return "echo=" + body
        }

        @Get("/headers")
        String headers(io.micronaut.http.HttpHeaders headers) {
            return "headers=" + headers.getAll("X-Test")
        }

        @Get("/admin")
        String admin() {
            return "admin"
        }
    }
}
