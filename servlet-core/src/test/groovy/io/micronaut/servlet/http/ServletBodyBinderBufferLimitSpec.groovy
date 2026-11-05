package io.micronaut.servlet.http

import io.micronaut.core.convert.ConversionService
import io.micronaut.http.bind.binders.DefaultBodyAnnotationBinder
import io.micronaut.http.body.MessageBodyHandlerRegistry
import io.micronaut.http.exceptions.BufferLengthExceededException
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * A body decoded in memory from the stream of the servlet request, e.g. by an {@code AsyncRequestBody}, is held to the
 * buffer limit whatever the bytes arrive like: the stream is a streaming read of the body, which the body does not
 * hold to that limit once the read subscribed before the bytes arrived.
 */
class ServletBodyBinderBufferLimitSpec extends Specification {

    void "a body decoded in memory fails past the buffer limit, wherever the bytes are"() {
        given:
        def binder = binder(1024)
        def request = request(4096)

        when:
        binder.bufferedStream(request).readAllBytes()

        then:
        def e = thrown(BufferLengthExceededException)
        e.message.contains("exceeds the maximum allowed bufferable length [1024]")
    }

    void "a text body decoded in memory fails past the buffer limit"() {
        when:
        binder(1024).bufferedReader(request(4096)).text

        then:
        thrown(BufferLengthExceededException)
    }

    void "a body within the buffer limit is read whole"() {
        expect:
        binder(1024).bufferedStream(request(1024)).readAllBytes().length == 1024
    }

    void "without a limit the stream of the request is read as it is"() {
        given:
        def request = request(4096)

        expect:
        binder(Long.MAX_VALUE).bufferedStream(request).readAllBytes().length == 4096
    }

    private ServletBodyBinder<Object> binder(long maxBufferSize) {
        new ServletBodyBinder<Object>(ConversionService.SHARED, Stub(MessageBodyHandlerRegistry), new DefaultBodyAnnotationBinder<Object>(ConversionService.SHARED), null, maxBufferSize)
    }

    private ServletHttpRequest<?, ?> request(int length) {
        ServletHttpRequest<?, ?> request = Stub()
        request.getInputStream() >> { new ByteArrayInputStream(new byte[length]) }
        request.getCharacterEncoding() >> StandardCharsets.UTF_8
        request
    }
}
