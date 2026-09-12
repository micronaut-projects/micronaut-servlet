package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * Connection handling as seen on the wire, using a raw socket so nothing between the test and the server
 * negotiates keep-alive on its behalf.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'JettyConnectionHeaderSpec')
class JettyConnectionHeaderSpec extends Specification {

    @Inject
    EmbeddedServer embeddedServer

    void "a successful response keeps the connection open for the next request"() {
        when:
        List<String> responses = exchangeOnOneConnection(['/connection/ok', '/connection/ok'])

        then: "two responses arrive on the same connection"
        responses.size() == 2
        responses.every { it.startsWith('HTTP/1.1 200') }
    }

    void "a client asking to close is honoured"() {
        when:
        String response = rawRequest('/connection/ok', 'Connection: close')

        then:
        response.startsWith('HTTP/1.1 200')
        response.toLowerCase().contains('connection: close')
    }

    void "a server error still delivers a well formed response"() {
        when:
        String response = rawRequest('/connection/fail', 'Connection: keep-alive')

        then:
        response.startsWith('HTTP/1.1 500')
    }

    private String rawRequest(String path, String extraHeader) {
        try (Socket socket = new Socket(embeddedServer.host, embeddedServer.port)) {
            socket.soTimeout = 5000
            socket.outputStream.write("GET ${path} HTTP/1.1\r\nHost: localhost\r\n${extraHeader}\r\n\r\n".getBytes('ISO-8859-1'))
            socket.outputStream.flush()
            return readResponse(socket.inputStream)
        }
    }

    private List<String> exchangeOnOneConnection(List<String> paths) {
        try (Socket socket = new Socket(embeddedServer.host, embeddedServer.port)) {
            socket.soTimeout = 5000
            List<String> out = []
            paths.each { path ->
                socket.outputStream.write("GET ${path} HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes('ISO-8859-1'))
                socket.outputStream.flush()
                out << readResponse(socket.inputStream)
            }
            return out
        }
    }

    /** Reads one response: headers, then a body of the declared length or the chunked frames. */
    private static String readResponse(InputStream input) {
        StringBuilder head = new StringBuilder()
        int prev = -1
        int c
        while ((c = input.read()) != -1) {
            head.append((char) c)
            if (head.toString().endsWith('\r\n\r\n')) break
        }
        String headers = head.toString()
        def match = headers =~ /(?i)content-length:\s*(\d+)/
        if (match.find()) {
            int len = match.group(1) as int
            byte[] body = new byte[len]
            int read = 0
            while (read < len) {
                int n = input.read(body, read, len - read)
                if (n == -1) break
                read += n
            }
        } else if (headers.toLowerCase().contains('transfer-encoding: chunked')) {
            while (true) {
                StringBuilder line = new StringBuilder()
                while ((c = input.read()) != -1) { line.append((char) c); if (line.toString().endsWith('\r\n')) break }
                int size = Integer.parseInt(line.toString().trim(), 16)
                if (size == 0) { input.read(); input.read(); break }
                input.readNBytes(size + 2)
            }
        }
        return headers
    }

    @Requires(property = 'spec.name', value = 'JettyConnectionHeaderSpec')
    @Controller('/connection')
    static class ConnectionController {

        @Get(value = '/ok', produces = MediaType.TEXT_PLAIN)
        String ok() {
            'ok'
        }

        @Get('/fail')
        String fail() {
            throw new IllegalStateException('deliberate')
        }
    }
}
