package io.micronaut.servlet.http

import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.exceptions.HttpStatusException
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class BufferedFormDecoderSpec extends Specification {

    void "a line that only starts like the delimiter is content of the part"() {
        given:
        String content = "a\r\n--bnd" + suffix + "\r\n\r\nb"
        byte[] body = ("--bnd\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f.txt\"\r\n\r\n" + content + "\r\n--bnd--\r\n").getBytes(StandardCharsets.ISO_8859_1)

        when:
        def fields = BufferedFormDecoder.decode(MediaType.of("multipart/form-data; boundary=bnd"), body, StandardCharsets.UTF_8)

        then:
        fields.size() == 1
        fields[0].metadata().name() == "file"
        new String(fields[0].content(), StandardCharsets.ISO_8859_1) == content

        where:
        suffix << ["x", "-x", " x"]
    }

    void "a delimiter followed by transport padding ends the part"() {
        given:
        byte[] body = "--bnd\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\none\r\n--bnd \t\r\nContent-Disposition: form-data; name=\"b\"\r\n\r\ntwo\r\n--bnd--".getBytes(StandardCharsets.ISO_8859_1)

        when:
        def fields = BufferedFormDecoder.decode(MediaType.of("multipart/form-data; boundary=bnd"), body, StandardCharsets.UTF_8)

        then:
        fields*.metadata()*.name() == ["a", "b"]
        fields.collect { new String(it.content(), StandardCharsets.ISO_8859_1) } == ["one", "two"]
    }

    void "a delimiter in the middle of a line is content of the part"() {
        given:
        String content = "prefix--bnd--suffix"
        byte[] body = ("--bnd\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f.txt\"\r\n\r\n" + content + "\r\n--bnd--\r\n").getBytes(StandardCharsets.ISO_8859_1)

        when:
        def fields = BufferedFormDecoder.decode(MediaType.of("multipart/form-data; boundary=bnd"), body, StandardCharsets.UTF_8)

        then:
        fields.size() == 1
        new String(fields[0].content(), StandardCharsets.ISO_8859_1) == content
    }

    void "a close delimiter followed by something else than a line end is content of the part"() {
        given:
        String content = "a\r\n--bnd--suffix"
        byte[] body = ("--bnd\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n" + content + "\r\n--bnd--").getBytes(StandardCharsets.ISO_8859_1)

        when:
        def fields = BufferedFormDecoder.decode(MediaType.of("multipart/form-data; boundary=bnd"), body, StandardCharsets.UTF_8)

        then:
        fields.size() == 1
        new String(fields[0].content(), StandardCharsets.ISO_8859_1) == content
    }

    void "a malformed escape of a URL-encoded form is a bad request"() {
        when:
        BufferedFormDecoder.decode(MediaType.APPLICATION_FORM_URLENCODED_TYPE, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)

        then:
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        body << ["a=%GG", "%G=b", "a=%"]
    }

    void "a form with more fields than the limit is refused"() {
        when:
        BufferedFormDecoder.decode(MediaType.of(contentType), body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8, 2, Long.MAX_VALUE)

        then:
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.REQUEST_ENTITY_TOO_LARGE

        where:
        contentType                         | body
        "application/x-www-form-urlencoded" | "a=1&b=2&c=3"
        "multipart/form-data; boundary=bnd" | "--bnd\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--bnd\r\nContent-Disposition: form-data; name=\"b\"\r\n\r\n2\r\n--bnd\r\nContent-Disposition: form-data; name=\"c\"\r\n\r\n3\r\n--bnd--"
    }

    void "a form with as many fields as the limit is decoded"() {
        expect:
        BufferedFormDecoder.decode(MediaType.APPLICATION_FORM_URLENCODED_TYPE, "a=1&b=2".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, 2, Long.MAX_VALUE).size() == 2
    }

    void "a file larger than the file size limit is refused"() {
        given:
        byte[] body = "--bnd\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n12345678\r\n--bnd\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f.txt\"\r\n\r\n12345\r\n--bnd--".getBytes(StandardCharsets.ISO_8859_1)

        when:
        BufferedFormDecoder.decode(MediaType.of("multipart/form-data; boundary=bnd"), body, StandardCharsets.UTF_8, 10, 4)

        then:
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.REQUEST_ENTITY_TOO_LARGE

        when: "the limit applies to files only"
        def fields = BufferedFormDecoder.decode(MediaType.of("multipart/form-data; boundary=bnd"), body, StandardCharsets.UTF_8, 10, 5)

        then:
        fields.size() == 2
    }
}
