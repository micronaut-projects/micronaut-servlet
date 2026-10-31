package io.micronaut.servlet.engine

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import spock.lang.Specification

import java.nio.ByteBuffer

class ServletStreamPublisherSpec extends Specification {

    void "each read buffer is sized by what is left of a body with a declared length, and bounded otherwise"() {
        given:
        byte[] body = new byte[bodyLength]
        RecordingInputStream stream = new RecordingInputStream(body)
        ServletStreamPublisher publisher = new ServletStreamPublisher({ stream }, Long.MAX_VALUE, declaredLength)
        List<Integer> chunks = []
        boolean completed = false

        when:
        publisher.subscribe(new Subscriber<ByteBuffer>() {
            void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE) }
            void onNext(ByteBuffer b) { chunks << b.remaining() }
            void onError(Throwable t) { throw t }
            void onComplete() { completed = true }
        })
        stream.listener.onDataAvailable()

        then:
        completed
        chunks.sum() == bodyLength
        stream.bufferSizes == expectedBuffers

        where:
        bodyLength | declaredLength | expectedBuffers
        20         | 20             | [20, 1]
        10000      | 10000          | [8192, 1808, 1]
        10000      | -1             | [8192, 8192, 8192]
    }

    static class RecordingInputStream extends ServletInputStream {
        final ByteArrayInputStream input
        final List<Integer> bufferSizes = []
        ReadListener listener

        RecordingInputStream(byte[] body) {
            input = new ByteArrayInputStream(body)
        }

        boolean isFinished() { input.available() == 0 }

        boolean isReady() { true }

        void setReadListener(ReadListener readListener) { listener = readListener }

        int read() { input.read() }

        int read(byte[] b, int off, int len) {
            bufferSizes << b.length
            input.read(b, off, len)
        }
    }
}
