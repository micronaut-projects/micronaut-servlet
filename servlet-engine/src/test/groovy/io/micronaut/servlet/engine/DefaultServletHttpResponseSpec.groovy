package io.micronaut.servlet.engine

import io.micronaut.core.convert.ConversionService
import io.micronaut.core.io.buffer.ByteArrayBufferFactory
import io.micronaut.http.body.ByteBodyFactory
import io.micronaut.servlet.http.BodyBuilder
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
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

    private DefaultServletHttpResponse<?> newResponse(HttpServletResponse servletResponse) {
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
            null,
            bodyBuilder,
            executor,
            null
        )
        return new DefaultServletHttpResponse<>(ConversionService.SHARED, request, servletResponse)
    }

    private static final class CapturingServletOutputStream extends ServletOutputStream {
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
    }
}
