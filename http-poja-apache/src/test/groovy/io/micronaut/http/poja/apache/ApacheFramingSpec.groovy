package io.micronaut.http.poja.apache

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.poja.PojaConnection
import io.micronaut.runtime.ApplicationConfiguration
import io.micronaut.servlet.http.ServletHttpHandler
import org.jspecify.annotations.Nullable
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.nio.charset.StandardCharsets

/**
 * The framing of requests and responses on a connection, driven through streams.
 */
@Timeout(30)
class ApacheFramingSpec extends Specification {

    static final int MAX_REQUEST_SIZE = 1024

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run([
        'spec.name'                       : 'ApacheFramingSpec',
        'micronaut.server.max-request-size': MAX_REQUEST_SIZE,
    ], 'test')

    @Shared
    ApacheServerlessApplication application = context.getBean(ApacheServerlessApplication)

    void "an endless chunked body that the route ignores is drained no further than the size limit, and the connection is closed"() {
        given:
        def out = new ByteArrayOutputStream()
        def endless = new EndlessChunkedInputStream("POST /framing/ignore HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n")

        when:
        String response = serve(endless, out)

        then:
        response.startsWith('HTTP/1.1 200')
        response.endsWith('ignored')
        endless.produced < MAX_REQUEST_SIZE + 64 * 1024 + 3 * 8192
    }

    void "an endless chunked body that the route reads answers 413"() {
        given:
        def out = new ByteArrayOutputStream()
        def endless = new EndlessChunkedInputStream("POST /framing/echo HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n")

        when:
        String response = serve(endless, out)

        then:
        response.startsWith('HTTP/1.1 413')
        endless.produced < MAX_REQUEST_SIZE + 64 * 1024 + 3 * 8192
    }

    void "a chunked body over the limit answers 413 and is not followed by another request"() {
        given:
        String body = 'x' * (MAX_REQUEST_SIZE + 10)
        String request = "POST /framing/echo HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n" +
            Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n" +
            "GET /framing/get HTTP/1.1\r\nHost: h\r\n\r\n"

        when:
        String response = serve(request)

        then:
        response.startsWith('HTTP/1.1 413')
        responses(response) == 1
    }

    void "the body of a #method request is consumed by its framing, not parsed as another request"() {
        given:
        String smuggled = "GET /framing/smuggled HTTP/1.1\r\nHost: h\r\n\r\n"
        String framing = chunked
            ? "Transfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(smuggled.length()) + "\r\n" + smuggled + "\r\n0\r\n\r\n"
            : "Content-Length: ${smuggled.length()}\r\n\r\n" + smuggled
        String request = "$method /framing/get HTTP/1.1\r\nHost: h\r\n" + framing +
            "GET /framing/get HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"

        when:
        String response = serve(request)

        then:
        responses(response) == 2
        !response.contains('404')
        !response.contains('smuggled')

        where:
        method | chunked
        'GET'  | false
        'GET'  | true
        'HEAD' | false
        'HEAD' | true
    }

    void "a Content-Length ignored for a chunked body does not limit the body"() {
        when:
        String response = serve("POST /framing/echo HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\n" +
            "Transfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n5\r\nhello\r\n0\r\n\r\n")

        then:
        response.startsWith('HTTP/1.1 200')
        response.endsWith('echo:hello')
    }

    void "Content-Length #value is refused with 400"() {
        when:
        String response = serve("POST /framing/echo HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\n$value\r\n\r\nx")

        then:
        response.startsWith('HTTP/1.1 400')
        responses(response) == 1

        where:
        value << ['Content-Length: +1', 'Content-Length: -0', 'Content-Length: 1\r\nContent-Length: 2', 'Content-Length: 1,1']
    }

    void "a connection that ends after its last request gets no further response"() {
        when:
        String response = serve("GET /framing/get HTTP/1.1\r\nHost: h\r\n\r\n")

        then:
        response.startsWith('HTTP/1.1 200')
        responses(response) == 1
        !response.contains('400')
    }

    void "a 204 response has no Content-Length, while a HEAD response keeps it"() {
        when:
        String noContent = serve("GET /framing/no-content HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")
        String head = serve("HEAD /framing/get HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")

        then:
        noContent.startsWith('HTTP/1.1 204')
        !noContent.toLowerCase(Locale.ROOT).contains('content-length')
        head.startsWith('HTTP/1.1 200')
        head.toLowerCase(Locale.ROOT).contains('content-length: 3')
        !head.endsWith('get')
    }

    void "pipelined requests read at once are both served through the #name entry point"() {
        given:
        def subclass = new SingleRequestApplication(context, context.getBean(ApplicationConfiguration), deprecated)
        def in = new ByteArrayInputStream(("GET /framing/get HTTP/1.1\r\nHost: h\r\n\r\n" +
            "POST /framing/echo HTTP/1.1\r\nHost: h\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nhi")
            .getBytes(StandardCharsets.UTF_8))
        def out = new ByteArrayOutputStream()

        when:
        subclass.serve(in, out, PojaConnection.UNKNOWN)
        String response = out.toString(StandardCharsets.UTF_8)

        then:
        responses(response) == 2
        response.endsWith('echo:hi')

        where:
        name                | deprecated
        'connection'        | false
        'deprecated'        | true
    }

    private String serve(String request) {
        return serve(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream())
    }

    private String serve(InputStream input, ByteArrayOutputStream out) {
        try {
            application.serve(input, out, PojaConnection.UNKNOWN)
        } catch (RuntimeException ignored) {
            // a bad request ends the connection after its 400
        }
        return out.toString(StandardCharsets.UTF_8)
    }

    private static int responses(String response) {
        return response.findAll(/HTTP\/1\.1 \d{3} /).size()
    }

    /**
     * The handler of every request as a subclass that calls one of the compatibility entry points per request.
     */
    static class SingleRequestApplication extends ApacheServerlessApplication {
        private final boolean deprecated

        SingleRequestApplication(ApplicationContext applicationContext, ApplicationConfiguration applicationConfiguration, boolean deprecated) {
            super(applicationContext, applicationConfiguration)
            this.deprecated = deprecated
        }

        @Override
        protected void runIndefinitely(ServletHttpHandler<ApacheServletHttpRequest<?>, ApacheServletHttpResponse<?>> handler,
                                       InputStream in, OutputStream out, PojaConnection connection) throws IOException {
            while (deprecated ? handleSingleRequest(handler, in, out) : handleSingleRequest(handler, in, out, connection)) {
                // one request per call
            }
        }
    }

    /**
     * Request headers followed by chunks without an end, counting what was read.
     */
    static class EndlessChunkedInputStream extends InputStream {
        private final byte[] head
        private final byte[] chunk
        private int position
        long produced

        EndlessChunkedInputStream(String head) {
            this.head = head.getBytes(StandardCharsets.UTF_8)
            this.chunk = ("100\r\n" + ('x' * 256) + "\r\n").getBytes(StandardCharsets.UTF_8)
        }

        @Override
        int read() throws IOException {
            byte[] b = new byte[1]
            return read(b, 0, 1) < 0 ? -1 : (b[0] & 0xff)
        }

        @Override
        int read(byte[] b, int off, int len) throws IOException {
            int n = 0
            while (n < len) {
                if (position < head.length) {
                    b[off + n++] = head[position++]
                } else {
                    int i = (position - head.length) % chunk.length
                    b[off + n++] = chunk[i]
                    position++
                    produced++
                }
                if (produced > 100L * 1024 * 1024) {
                    throw new IOException("read too far")
                }
            }
            return n
        }
    }

    @Controller('/framing')
    @Requires(property = 'spec.name', value = 'ApacheFramingSpec')
    static class FramingController {

        @Post('/ignore')
        String ignore() {
            'ignored'
        }

        @Post(value = '/echo', processes = 'text/plain')
        String echo(@Body @Nullable String body) {
            'echo:' + (body ?: '')
        }

        @Get(value = '/get', produces = 'text/plain')
        String get() {
            'get'
        }

        @Get(value = '/smuggled', produces = 'text/plain')
        String smuggled() {
            'smuggled'
        }

        @Get('/no-content')
        HttpResponse<?> noContent() {
            HttpResponse.noContent().header('Content-Length', '0')
        }
    }
}
