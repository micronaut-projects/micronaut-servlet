package io.micronaut.servlet.undertow

import io.undertow.server.HttpServerExchange
import io.undertow.servlet.spec.HttpServletRequestImpl
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import spock.lang.Specification

class UndertowConnectionAborterSpec extends Specification {

    void "the connection of a request a filter wrapped is dropped too"() {
        given:
        def exchange = new HttpServerExchange(null)
        exchange.persistent = true
        def wrapped = new HttpServletRequestWrapper(new HttpServletRequestWrapper(new HttpServletRequestImpl(exchange, null)))

        expect:
        new UndertowConnectionAborter().abort(wrapped, Stub(HttpServletResponse), new IOException("failed"))
        !exchange.persistent
    }
}
