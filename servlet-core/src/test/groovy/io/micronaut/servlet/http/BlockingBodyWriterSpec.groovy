package io.micronaut.servlet.http

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.core.publisher.Flux
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class BlockingBodyWriterSpec extends Specification {

    void "a piece that arrived before the body failed is written before the failure is reported"() {
        given: "a body that hands over a piece and fails before the writer read it, like an event stream whose handler fails right after an event"
        Publisher<byte[]> body = new FailingAfterOnePiece()
        def out = new ByteArrayOutputStream()

        when:
        BlockingBodyWriter.write(body, out)

        then:
        def e = thrown(IOException)
        e.cause.message == 'database went away'
        out.toString(StandardCharsets.UTF_8) == 'data: one\n\n'
    }

    void "every piece is written and flushed in order"() {
        given:
        def flushes = 0
        def out = new ByteArrayOutputStream() {
            @Override
            void flush() {
                flushes++
            }
        }

        when:
        BlockingBodyWriter.write(Flux.just('a', 'b', 'c').map { it.getBytes(StandardCharsets.UTF_8) }, out)

        then:
        out.toString(StandardCharsets.UTF_8) == 'abc'
        flushes == 3
    }

    void "a failure to write cancels the body"() {
        given:
        def cancelled = false
        def body = Flux.just('a'.bytes, 'b'.bytes).doOnCancel { cancelled = true }
        def out = new OutputStream() {
            @Override
            void write(int b) {
                throw new IOException('broken pipe')
            }
        }

        when:
        BlockingBodyWriter.write(body, out)

        then:
        thrown(IOException)
        cancelled
    }

    /**
     * Hands over a piece and fails at once, before the writer read the piece.
     */
    static class FailingAfterOnePiece implements Publisher<byte[]> {
        @Override
        @SuppressWarnings('rawtypes') // Groovy writes a broken generic signature for Subscriber<? super byte[]>
        void subscribe(Subscriber s) {
            s.onSubscribe(new Subscription() {
                boolean sent

                @Override
                void request(long n) {
                    if (!sent) {
                        sent = true
                        s.onNext('data: one\n\n'.getBytes(StandardCharsets.UTF_8))
                        s.onError(new IllegalStateException('database went away'))
                    }
                }

                @Override
                void cancel() {
                }
            })
        }
    }
}
