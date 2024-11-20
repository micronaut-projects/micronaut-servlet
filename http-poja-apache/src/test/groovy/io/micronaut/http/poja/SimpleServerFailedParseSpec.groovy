package io.micronaut.http.poja

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification
import spock.lang.Stepwise

@Stepwise
@MicronautTest
class SimpleServerFailedParseSpec extends Specification {

    @Inject
    SimpleServerSpec.StringTestingClient client

    void "test non-parseable GET method"() {
        when:
        var response = client.exchange(SimpleServerSpec.unindent("""
        GET /test HTTP/1.1error\r
        Host: h\r
        \r
        """))

        then:
        response == SimpleServerSpec.unindent("""
        HTTP/1.1 400 Bad Request\r
        Transfer-Encoding: chunked\r
        \r
        20\r
        HTTP request could not be parsed\r
        0\r
        \r
        """)
    }

}
