package io.micronaut.servlet.tomcat

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponseWrapper
import org.apache.catalina.connector.Response
import org.apache.catalina.connector.ResponseFacade
import spock.lang.Specification

class TomcatConnectionAborterSpec extends Specification {

    void "the connection of a response a filter wrapped is dropped too"() {
        given:
        def facade = new ResponseFacade(new Response(new org.apache.coyote.Response()))
        def wrapped = new HttpServletResponseWrapper(new HttpServletResponseWrapper(facade))

        expect:
        new TomcatConnectionAborter().abort(Stub(HttpServletRequest), facade, new IOException("failed"))
        new TomcatConnectionAborter().abort(Stub(HttpServletRequest), wrapped, new IOException("failed"))
    }
}
