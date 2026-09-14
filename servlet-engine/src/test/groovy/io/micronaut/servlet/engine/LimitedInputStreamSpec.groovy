package io.micronaut.servlet.engine

import io.micronaut.http.exceptions.ContentLengthExceededException
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * The stream that enforces the request size limit counts every way the body can be consumed.
 */
class LimitedInputStreamSpec extends Specification {

    void "a body within the limit is read unchanged, byte by byte or in bulk"() {
        given:
        LimitedInputStream stream = new LimitedInputStream(stream('hello world'), 11)

        when:
        int first = stream.read()
        byte[] rest = new byte[10]
        int read = stream.read(rest, 0, rest.length)

        then:
        first == (int) 'h'
        read == 10
        new String(rest, 0, read, StandardCharsets.UTF_8) == 'ello world'
        stream.read() == -1
    }

    void "reading a body past the limit fails on the byte that exceeds it"() {
        given:
        LimitedInputStream stream = new LimitedInputStream(stream('hello world'), 5)

        when:
        5.times { stream.read() }

        then:
        noExceptionThrown()

        when:
        stream.read()

        then:
        ContentLengthExceededException e = thrown()
        e.message.contains('5')
    }

    void "bulk reads and skipped bytes count against the limit too"() {
        when:
        LimitedInputStream bulk = new LimitedInputStream(stream('hello world'), 5)
        bulk.read(new byte[8], 0, 8)

        then:
        thrown(ContentLengthExceededException)

        when:
        LimitedInputStream skipping = new LimitedInputStream(stream('hello world'), 5)
        long skipped = skipping.skip(4)

        then:
        skipped == 4

        when:
        skipping.skip(4)

        then:
        thrown(ContentLengthExceededException)
    }

    private static InputStream stream(String text) {
        new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))
    }
}
