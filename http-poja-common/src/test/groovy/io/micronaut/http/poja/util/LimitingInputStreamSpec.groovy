package io.micronaut.http.poja.util

import io.micronaut.servlet.http.body.InputStreamByteBody
import spock.lang.Specification

import java.util.concurrent.Executors

class LimitingInputStreamSpec extends Specification {

    void "test LimitingInputStream"() {
        when:
        var stream = new ByteArrayInputStream("Hello world!".bytes)
        var limiting = new LimitingInputStream(stream, 5)

        then:
        new String(limiting.readAllBytes()) == "Hello"
    }

    void "test LimitingInputStream with ByteBody"() {
        when:
        var stream = new ByteArrayInputStream("Hello world!".bytes)
        var limiting = new LimitingInputStream(stream, 5)
        var executor = Executors.newFixedThreadPool(1)
        var body = InputStreamByteBody.create(limiting, OptionalLong.empty(), executor)

        then:
        new String(body.toInputStream().readAllBytes()) == "Hello"
    }

    void "test LimitingInputStream with larger limit"() {
        when:
        var stream = new ByteArrayInputStream("Hello".bytes)
        var limiting = new LimitingInputStream(stream, 100)

        then:
        new String(limiting.readAllBytes()) == "Hello"
    }

}
