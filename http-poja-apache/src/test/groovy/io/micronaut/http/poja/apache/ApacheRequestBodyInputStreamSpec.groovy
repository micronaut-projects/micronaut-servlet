package io.micronaut.http.poja.apache

import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ApacheRequestBodyInputStreamSpec extends Specification {

    void "the discard does not read the framing stream while the application is reading it"() {
        given: "a framing stream that records overlapping reads"
        CountDownLatch inRead = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)
        AtomicInteger active = new AtomicInteger()
        AtomicInteger maxActive = new AtomicInteger()
        InputStream framing = new InputStream() {
            int remaining = 1000

            @Override
            int read() {
                throw new UnsupportedOperationException()
            }

            @Override
            int read(byte[] b, int off, int len) {
                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max)
                try {
                    if (inRead.count > 0) {
                        inRead.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    if (remaining == 0) {
                        return -1
                    }
                    int n = Math.min(len, remaining)
                    remaining -= n
                    return n
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        def body = new ApacheRequestBodyInputStream(framing, -1, null)

        when: "the application is blocked in a read on another thread while the runtime discards the body"
        Thread consumer = Thread.start { body.read(new byte[10], 0, 10) }
        inRead.await(5, TimeUnit.SECONDS)
        boolean discarded
        Thread runtime = Thread.start { discarded = body.discardRemaining() }
        Thread.sleep(100)
        release.countDown()
        consumer.join(5000)
        runtime.join(5000)

        then: "the reads never overlap and the body is consumed to its end"
        maxActive.get() == 1
        discarded

        when: "the application reads again"
        body.read(new byte[10], 0, 10)

        then:
        thrown(IOException)
    }

    void "whether the body is finished can be asked while the application is blocked in a read"() {
        given:
        CountDownLatch inRead = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)
        InputStream framing = new InputStream() {
            @Override
            int read() {
                throw new UnsupportedOperationException()
            }

            @Override
            int read(byte[] b, int off, int len) {
                inRead.countDown()
                release.await(5, TimeUnit.SECONDS)
                return -1
            }
        }
        def body = new ApacheRequestBodyInputStream(framing, -1, null)
        Thread consumer = Thread.start { body.read(new byte[10], 0, 10) }
        inRead.await(5, TimeUnit.SECONDS)

        when: "the runtime checks the body while the application holds the stream"
        long start = System.nanoTime()
        boolean finished = body.isFinished()
        long elapsedMillis = (System.nanoTime() - start).intdiv(1_000_000)

        then: "it does not wait for the application's read"
        !finished
        elapsedMillis < 1000

        cleanup:
        release.countDown()
        consumer.join(5000)
    }

    void "the body cannot be taken over while the application is blocked in a read, and can once it is not"() {
        given:
        CountDownLatch inRead = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)
        InputStream framing = new InputStream() {
            @Override
            int read() {
                throw new UnsupportedOperationException()
            }

            @Override
            int read(byte[] b, int off, int len) {
                inRead.countDown()
                release.await(5, TimeUnit.SECONDS)
                return 1
            }
        }
        def body = new ApacheRequestBodyInputStream(framing, -1, null)
        Thread consumer = Thread.start { body.read(new byte[10], 0, 10) }
        inRead.await(5, TimeUnit.SECONDS)

        expect: "the runtime leaves the connection input alone while the application reads it"
        !body.tryDetach()

        when:
        release.countDown()
        consumer.join(5000)

        then:
        body.tryDetach()

        when: "the application reads after the runtime has taken the body over"
        body.read(new byte[10], 0, 10)

        then:
        thrown(IOException)
    }

    void "a body that expects 100 Continue sends it before the first read only"() {
        given:
        int sent = 0
        def body = new ApacheRequestBodyInputStream(new ByteArrayInputStream("hello".bytes), -1, { -> sent++; true })

        when:
        body.read()
        body.read(new byte[10], 0, 10)

        then:
        sent == 1
        body.discardRemaining()
    }

    void "a body that expects 100 Continue and was never read cannot be discarded"() {
        given:
        def body = new ApacheRequestBodyInputStream(new ByteArrayInputStream("hello".bytes), -1, { -> true })

        expect: "the client has not sent it, so the connection has to be closed"
        !body.discardRemaining()
    }

    void "a body first read after the final response fails instead of waiting for the withheld body"() {
        given:
        def body = new ApacheRequestBodyInputStream(new ByteArrayInputStream("hello".bytes), -1, { -> false })

        when:
        body.read(new byte[10], 0, 10)

        then:
        thrown(IOException)
        !body.discardRemaining()
    }

    void "single byte reads count towards the limit"() {
        given:
        def body = new ApacheRequestBodyInputStream(new ByteArrayInputStream("hello".bytes), 3, null)

        when:
        4.times { body.read() }

        then:
        thrown(io.micronaut.http.exceptions.ContentLengthExceededException)
        !body.discardRemaining()
    }
}
