package io.micronaut.servlet.http

import io.micronaut.http.MediaType
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
}
