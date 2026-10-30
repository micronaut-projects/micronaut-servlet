package io.micronaut.servlet.http

import io.micronaut.http.codec.CodecException
import io.micronaut.http.exceptions.ContentLengthExceededException
import spock.lang.Specification

class ServletBodyBinderDecodingFailureSpec extends Specification {

    void "a reader failing to decode a body of a custom format is a request body decoding failure, answered with 400"() {
        given:
        def failure = new CodecException("not a valid document")

        when:
        def result = ServletBodyBinder.decodingFailure("Unable to decode request body: ", failure)

        then:
        result instanceof RequestBodyDecodingException
        result.cause.is(failure)
    }

    void "a request body decoding failure and a size limit are kept as they are"() {
        expect:
        ServletBodyBinder.decodingFailure("x", failure).is(failure)

        where:
        failure << [new RequestBodyDecodingException("bad", null), new ContentLengthExceededException(1, 2)]
    }
}
