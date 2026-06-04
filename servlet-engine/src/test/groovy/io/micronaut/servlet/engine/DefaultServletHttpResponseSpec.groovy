package io.micronaut.servlet.engine

import io.micronaut.core.convert.ConversionService
import io.micronaut.core.io.buffer.ByteArrayBufferFactory
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpResponseProvider
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Produces
import io.micronaut.http.body.ByteBodyFactory
import io.micronaut.http.body.MessageBodyHandlerRegistry
import io.micronaut.http.body.MessageBodyWriter
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.servlet.http.BodyBuilder
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import reactor.core.publisher.Flux
import spock.lang.Specification

import java.nio.ByteBuffer
import java.util.concurrent.Executor

class DefaultServletHttpResponseSpec extends Specification {

    void "stream closeable byte body writes bytes and completes after all data is flushed"() {
        given:
        def output = new CapturingServletOutputStream()
        long contentLength = -1
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
            setContentLengthLong(_ as Long) >> { long length -> contentLength = length }
        }
        def response = newResponse(servletResponse)
        def body = ByteBodyFactory.createDefault(ByteArrayBufferFactory.INSTANCE).adapt("hello".bytes)

        when:
        def result = response.stream(body)

        then:
        result.get() == null
        output.toByteArray() == "hello".bytes
        contentLength == 5
    }

    void "stream closeable byte body falls back when byte buffer writes are unavailable"() {
        given:
        def output = new CapturingServletOutputStream() {
            @Override
            void write(ByteBuffer buffer) {
                throw new NoSuchMethodError("write(ByteBuffer)")
            }
        }
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
        }
        def response = newResponse(servletResponse)
        def body = ByteBodyFactory.createDefault(ByteArrayBufferFactory.INSTANCE).adapt("fallback".bytes)

        when:
        def result = response.stream(body)

        then:
        result.get() == null
        output.toString() == "fallback"

        cleanup:
        def field = DefaultServletHttpResponse.getDeclaredField("writeBufferAvailable")
        field.accessible = true
        field.set(null, true)
    }

    void "stream publisher writes json delimiters for non-raw values"() {
        given:
        def output = new CapturingServletOutputStream()
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
            getContentType() >> null
        }
        def response = newResponse(servletResponse)

        when:
        def emitted = Flux.from(response.stream(Flux.just("one", "two"))).blockLast()

        then:
        emitted.is(response)
        output.toString() == "[one,two]"
    }

    void "stream publisher writes empty json array when no values are emitted"() {
        given:
        def output = new CapturingServletOutputStream()
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
            getContentType() >> null
        }
        def response = newResponse(servletResponse)

        when:
        def emitted = Flux.from(response.stream(Flux.empty())).blockLast()

        then:
        emitted.is(response)
        output.toString() == "[]"
    }

    void "stream publisher writes raw byte arrays without json delimiters"() {
        given:
        def output = new CapturingServletOutputStream()
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
            getContentType() >> MediaType.APPLICATION_OCTET_STREAM
        }
        def response = newResponse(servletResponse)

        when:
        def emitted = Flux.from(response.stream(Flux.just("raw".bytes))).blockLast()

        then:
        emitted.is(response)
        output.toString() == "raw"
    }

    void "stream publisher writes raw byte buffers without json delimiters"() {
        given:
        def output = new CapturingServletOutputStream()
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
            getContentType() >> MediaType.APPLICATION_OCTET_STREAM
        }
        def response = newResponse(servletResponse)
        def buffer = ByteArrayBufferFactory.INSTANCE.wrap("buffer".bytes)

        when:
        def emitted = Flux.from(response.stream(Flux.just(buffer))).blockLast()

        then:
        emitted.is(response)
        output.toString() == "buffer"
    }

    void "stream publisher writes objects with message body writer"() {
        given:
        def output = new CapturingServletOutputStream()
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
            getContentType() >> MediaType.APPLICATION_JSON
        }
        MessageBodyWriter<EncodedBody> writer = Stub(MessageBodyWriter) {
            writeTo(_, _, _ as EncodedBody, _, _ as OutputStream) >> { args ->
                args[4].write("encoded:${args[2].value}".bytes)
            }
        }
        MessageBodyHandlerRegistry registry = Stub(MessageBodyHandlerRegistry) {
            findWriter(_, _ as MediaType) >> Optional.empty()
            findWriter(_) >> Optional.of(writer)
        }
        def response = newResponse(servletResponse, registry)

        when:
        def emitted = Flux.from(response.stream(Flux.just(new EncodedBody("alpha")))).blockLast()

        then:
        emitted.is(response)
        output.toString() == "[encoded:alpha]"
    }

    void "stream publisher falls back to object string encoding"() {
        given:
        def output = new CapturingServletOutputStream()
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
            getContentType() >> MediaType.APPLICATION_JSON
        }
        def response = newResponse(servletResponse)

        when:
        def emitted = Flux.from(response.stream(Flux.just(new EncodedBody("fallback")))).blockLast()

        then:
        emitted.is(response)
        output.toString() == "[fallback]"
    }

    void "stream publisher forwards output stream acquisition errors"() {
        given:
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> { throw new IOException("no stream") }
            getContentType() >> null
        }
        def response = newResponse(servletResponse)

        when:
        Flux.from(response.stream(Flux.just("value"))).blockLast()

        then:
        def e = thrown(RuntimeException)
        e.cause instanceof IOException
        e.cause.message == "no stream"
    }

    void "stream publisher reports status errors before data is written"() {
        given:
        def output = new CapturingServletOutputStream()
        int status = 0
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
            getContentType() >> MediaType.TEXT_PLAIN
            setStatus(_ as Integer) >> { int code -> status = code }
        }
        def response = newResponse(servletResponse)

        when:
        def emitted = Flux.from(response.stream(Flux.error(new HttpStatusException(HttpStatus.BAD_REQUEST, "bad request")))).blockLast()

        then:
        emitted.is(response)
        status == HttpStatus.BAD_REQUEST.code
        output.toString() == "bad request"
    }

    void "stream publisher writes non-string status error bodies before data is written"() {
        given:
        def output = new CapturingServletOutputStream()
        int status = 0
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
            getContentType() >> MediaType.APPLICATION_JSON
            setStatus(_ as Integer) >> { int code -> status = code }
        }
        def response = newResponse(servletResponse)

        when:
        def emitted = Flux.from(response.stream(Flux.error(new HttpStatusException(HttpStatus.BAD_REQUEST, [message: "bad request"])))).blockLast()

        then:
        emitted.is(response)
        status == HttpStatus.BAD_REQUEST.code
        output.toString() == "[{message=bad request}"
    }

    void "stream publisher converts non-status errors before data is written"() {
        given:
        def output = new CapturingServletOutputStream()
        int status = 0
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
            getContentType() >> MediaType.TEXT_PLAIN
            setStatus(_ as Integer) >> { int code -> status = code }
        }
        def response = newResponse(servletResponse)

        when:
        def emitted = Flux.from(response.stream(Flux.error(new IllegalStateException("boom")))).blockLast()

        then:
        emitted.is(response)
        status == HttpStatus.INTERNAL_SERVER_ERROR.code
        output.toString() == "Internal Server Error: boom"
    }

    void "stream publisher forwards converted errors after data is written"() {
        given:
        def output = new CapturingServletOutputStream()
        int status = 0
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getOutputStream() >> output
            getContentType() >> null
            setStatus(_ as Integer) >> { int code -> status = code }
        }
        def response = newResponse(servletResponse)

        when:
        Flux.from(response.stream(Flux.concat(Flux.just("partial"), Flux.error(new IllegalStateException("boom"))))).blockLast()

        then:
        thrown(HttpStatusException)
        status == HttpStatus.INTERNAL_SERVER_ERROR.code
        output.toString() == "[partial"
    }

    void "body unwraps http response provider body"() {
        given:
        def response = newResponse(Stub(HttpServletResponse))

        when:
        response.body(new ProvidedResponse(HttpResponse.ok("provided")))

        then:
        response.getBody().get() == "provided"
    }

    void "body sets content type from produces annotation when absent"() {
        given:
        String contentType = null
        HttpServletResponse servletResponse = Stub(HttpServletResponse) {
            getContentType() >> { contentType }
            setContentType(_ as String) >> { String value -> contentType = value }
        }
        def response = newResponse(servletResponse)
        def body = new PlainTextBody()

        when:
        response.body(body)

        then:
        response.getBody().get().is(body)
        contentType == MediaType.TEXT_PLAIN
    }

    private DefaultServletHttpResponse<?> newResponse(
        HttpServletResponse servletResponse,
        MessageBodyHandlerRegistry messageBodyHandlerRegistry = null) {
        HttpServletRequest servletRequest = Stub(HttpServletRequest) {
            getContentLengthLong() >> -1
            isAsyncSupported() >> false
            getRequestURI() >> "/"
            getContextPath() >> ""
            getQueryString() >> null
            getMethod() >> "GET"
            getInputStream() >> Stub(ServletInputStream)
        }
        BodyBuilder bodyBuilder = Stub(BodyBuilder) {
            buildBody(_, _) >> null
        }
        Executor executor = Runnable::run
        def request = new DefaultServletHttpRequest<>(
            ConversionService.SHARED,
            servletRequest,
            servletResponse,
            messageBodyHandlerRegistry,
            bodyBuilder,
            executor,
            null
        )
        return new DefaultServletHttpResponse<>(ConversionService.SHARED, request, servletResponse)
    }

    private static class CapturingServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream()

        @Override
        boolean isReady() {
            true
        }

        @Override
        void setWriteListener(WriteListener writeListener) {
            writeListener.onWritePossible()
        }

        @Override
        void write(int b) {
            bytes.write(b)
        }

        @Override
        void write(byte[] b, int off, int len) {
            bytes.write(b, off, len)
        }

        @Override
        void write(ByteBuffer buffer) {
            if (buffer.hasArray()) {
                bytes.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
                buffer.position(buffer.limit())
            } else {
                while (buffer.hasRemaining()) {
                    bytes.write(buffer.get())
                }
            }
        }

        byte[] toByteArray() {
            bytes.toByteArray()
        }

        String toString() {
            bytes.toString("UTF-8")
        }
    }

    @Produces(MediaType.TEXT_PLAIN)
    private static final class PlainTextBody {
    }

    private static final class EncodedBody {
        private final String value

        private EncodedBody(String value) {
            this.value = value
        }

        @Override
        String toString() {
            value
        }
    }

    private static final class ProvidedResponse implements HttpResponseProvider {
        private final HttpResponse<?> response

        private ProvidedResponse(HttpResponse<?> response) {
            this.response = response
        }

        @Override
        HttpResponse<?> getResponse() {
            response
        }
    }
}
