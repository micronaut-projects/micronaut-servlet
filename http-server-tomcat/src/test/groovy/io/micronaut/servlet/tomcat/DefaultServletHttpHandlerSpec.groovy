package io.micronaut.servlet.tomcat

import io.micronaut.context.ApplicationContext
import io.micronaut.servlet.engine.DefaultServletHttpHandler
import io.micronaut.servlet.engine.InvalidRequestUriException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.ForkJoinPool

class DefaultServletHttpHandlerSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run()

    @Shared
    DefaultServletHttpHandler handler = new DefaultServletHttpHandler(context, context.conversionService, ForkJoinPool.commonPool(), null)

    void "service answers a malformed query string with a 400"() {
        given:
        HttpServletResponse response = Mock(HttpServletResponse)

        when:
        handler.service(malformedRequest(), response)

        then:
        noExceptionThrown()
        1 * response.setStatus(400)
    }

    void "exchange answers a malformed query string with a 400 before reporting it"() {
        given:
        HttpServletResponse response = Mock(HttpServletResponse)

        when:
        handler.exchange(malformedRequest(), response)

        then:
        thrown(InvalidRequestUriException)
        1 * response.setStatus(400)
    }

    private HttpServletRequest malformedRequest() {
        Stub(HttpServletRequest) {
            getMethod() >> 'GET'
            getRequestURI() >> '/path'
            getQueryString() >> 'a=%ZZ'
            getContentLengthLong() >> -1L
        }
    }
}
