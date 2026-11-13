package io.micronaut.http.poja

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.context.env.Environment
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.poja.apache.ApacheServerlessApplication
import io.micronaut.servlet.http.ServletExchange
import io.micronaut.servlet.http.ServletHttpHandler
import reactor.core.publisher.Flux
import spock.lang.Specification

import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets

/**
 * The serving loop survives a failed or disconnected response, and serves every request it has read.
 */
class ServingLoopSpec extends Specification {

    ApplicationContext context
    ApacheServerlessApplication application

    void setup() {
        context = ApplicationContext.run(['spec.name': 'ServingLoopSpec'], Environment.FUNCTION)
        application = context.getBean(ApacheServerlessApplication)
    }

    void cleanup() {
        Thread.interrupted()
        context.close()
    }

    void "a client disconnecting during the response does not fail the serving loop"() {
        given: "an output that fails once part of the response has been written"
        OutputStream out = new OutputStream() {
            int written

            @Override
            void write(int b) {
                if (++written > 200) {
                    throw new IOException("Broken pipe")
                }
            }
        }
        String request = "GET /loop/large HTTP/1.1\r\nHost: h\r\n\r\n"

        when:
        application.start(new ByteArrayInputStream(request.getBytes(StandardCharsets.ISO_8859_1)), out)

        then:
        noExceptionThrown()
    }

    void "a response that fails part way is not completed, so the client sees it truncated"() {
        given:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        String request = "GET /loop/failing HTTP/1.1\r\nHost: h\r\n\r\n"

        when:
        application.start(new ByteArrayInputStream(request.getBytes(StandardCharsets.ISO_8859_1)), out)
        String response = out.toString(StandardCharsets.ISO_8859_1)

        then:
        noExceptionThrown()
        response.startsWith("HTTP/1.1 200")
        response.contains("Transfer-Encoding: chunked")
        !response.endsWith("0\r\n\r\n")
    }

    void "requests pipelined into a single request call are all served"() {
        given:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        String requests = "GET /loop/a HTTP/1.1\r\nHost: h\r\n\r\nGET /loop/b HTTP/1.1\r\nHost: h\r\n\r\n"
        def handler = new ServletHttpHandler(context, context.getConversionService()) {
            @Override
            protected ServletExchange createExchange(Object request, Object response) {
                throw new UnsupportedOperationException()
            }
        }

        when:
        application.handleSingleRequest(handler, new ByteArrayInputStream(requests.getBytes(StandardCharsets.ISO_8859_1)), out)
        String response = out.toString(StandardCharsets.ISO_8859_1)

        then:
        response.contains("loop-a")
        response.contains("loop-b")
    }

    void "a body left unread on standard input closes the connection instead of waiting for it"() {
        given: "standard input that blocks once the part of the body the client sent has been read"
        PipedOutputStream client = new PipedOutputStream()
        PipedInputStream stdin = new PipedInputStream(client, 65536)
        InputStream originalIn = System.in
        System.setIn(stdin)
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        Thread writer = Thread.start {
            client.write("POST /loop/ignore HTTP/1.1\r\nHost: h\r\nContent-Length: 1000\r\n\r\n0123456789".getBytes(StandardCharsets.ISO_8859_1))
            client.flush()
            Thread.sleep(10_000) // keeps the pipe open, as a client that stalls would
        }

        when:
        Thread server = Thread.start { application.start(System.in, out) }
        server.join(5000)

        then: "the serving loop ends after the response rather than blocking on the rest of the body"
        !server.alive
        out.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 200")

        cleanup:
        System.setIn(originalIn)
        writer.interrupt()
    }

    void "only socket failures count as a client disconnect"() {
        expect:
        ApacheServerlessApplication.isDisconnect(new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Broken pipe").initCause(new SocketException("Broken pipe")))
        ApacheServerlessApplication.isDisconnect(new IOException("Connection reset by peer"))
        ApacheServerlessApplication.isDisconnect(new UncheckedIOException(new ClosedChannelException()))
        !ApacheServerlessApplication.isDisconnect(new UncheckedIOException(new FileNotFoundException("missing.txt")))
        !ApacheServerlessApplication.isDisconnect(new IllegalStateException("failed"))
    }

    @Requires(property = 'spec.name', value = 'ServingLoopSpec')
    @Controller(value = '/loop', produces = MediaType.TEXT_PLAIN)
    static class LoopController {

        @Get('/large')
        Flux<String> large() {
            return Flux.range(0, 1000).map { "line $it\n".toString() }
        }

        @Get('/failing')
        Flux<String> failing() {
            return Flux.concat(Flux.just("first\n"), Flux.error(new IllegalStateException("failed part way")))
        }

        @Post(value = '/ignore', consumes = MediaType.ALL)
        String ignore() {
            return 'ignored'
        }

        @Get('/a')
        String a() {
            return 'loop-a'
        }

        @Get('/b')
        String b() {
            return 'loop-b'
        }
    }
}
