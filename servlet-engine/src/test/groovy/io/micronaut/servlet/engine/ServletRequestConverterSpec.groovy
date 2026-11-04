package io.micronaut.servlet.engine

import io.micronaut.core.convert.ConversionContext
import io.micronaut.core.convert.ConversionService
import io.micronaut.core.convert.TypeConverter
import io.micronaut.core.type.Argument
import io.micronaut.servlet.http.BodyBuilder
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import spock.lang.Specification

import java.util.concurrent.Executor

class ServletRequestConverterSpec extends Specification {

    void "headers and parameters expose the conversion service of the request"() {
        given:
        def request = newRequest([:], [:])

        expect:
        request.headers.conversionService.is(ConversionService.SHARED)
        request.parameters.conversionService.is(ConversionService.SHARED)
    }

    void "a header is converted with the resolved converter"() {
        given:
        def request = newRequest(['X-Num': '42'], [:])
        TypeConverter<String, Integer> converter = Mock()

        when:
        def value = request.headers.get('X-Num', ConversionContext.of(Integer), converter)

        then:
        1 * converter.convert('42', Integer, _) >> Optional.of(42)
        value == Optional.of(42)

        when:
        def missing = request.headers.get('X-Missing', ConversionContext.of(Integer), converter)

        then:
        0 * converter.convert(*_)
        missing.isEmpty()
    }

    void "a header changed through the mutable view uses the converter too"() {
        given:
        def request = newRequest([:], [:])
        request.headers.add('X-Num', '7')

        expect:
        request.headers.get('X-Num', ConversionContext.of(Integer), ConversionService.SHARED.resolveConverter(String, Argument.of(Integer))) == Optional.of(7)
    }

    void "a parameter is converted with the resolved converter"() {
        given:
        def request = newRequest([:], [num: ['5', '6'] as String[]])
        TypeConverter<String, Integer> converter = Mock()

        when:
        def value = request.parameters.get('num', ConversionContext.of(Integer), converter)

        then:
        1 * converter.convert('5', Integer, _) >> Optional.of(5)
        value == Optional.of(5)
    }

    void "a string parameter skips the converter"() {
        given:
        def request = newRequest([:], [name: ['a'] as String[]])
        TypeConverter<String, String> converter = Mock()

        when:
        def value = request.parameters.get('name', ConversionContext.of(String), converter)

        then:
        0 * converter.convert(*_)
        value == Optional.of('a')
    }

    void "multivalue and optional parameters keep the conversion service semantics"() {
        given:
        def request = newRequest([:], [num: ['5', '6'] as String[]])
        TypeConverter converter = Mock()

        when:
        def list = request.parameters.get('num', ConversionContext.of(Argument.listOf(Integer)), converter)
        def optional = request.parameters.get('num', ConversionContext.of(Argument.of(Optional, Argument.listOf(Integer))), converter)

        then:
        0 * converter.convert(*_)
        list.get() == [5, 6]
        optional.isPresent()
    }

    private DefaultServletHttpRequest<?> newRequest(Map<String, String> headers, Map<String, String[]> parameters) {
        HttpServletRequest servletRequest = Stub(HttpServletRequest) {
            getContentLengthLong() >> -1
            isAsyncSupported() >> false
            getRequestURI() >> "/"
            getContextPath() >> ""
            getQueryString() >> null
            getMethod() >> "GET"
            getContentType() >> null
            getCharacterEncoding() >> null
            getInputStream() >> Stub(ServletInputStream)
            getHeader(_) >> { String n -> headers.find { it.key.equalsIgnoreCase(n) }?.value }
            getHeaders(_) >> { String n -> Collections.enumeration(headers.findAll { it.key.equalsIgnoreCase(n) }.values() as List) }
            getHeaderNames() >> { Collections.enumeration(headers.keySet()) }
            getParameterValues(_) >> { String n -> parameters[n] }
            getParameterNames() >> { Collections.enumeration(parameters.keySet()) }
        }
        BodyBuilder bodyBuilder = Stub(BodyBuilder)
        Executor executor = Runnable::run
        return new DefaultServletHttpRequest<>(ConversionService.SHARED, servletRequest, Stub(HttpServletResponse), null, bodyBuilder, executor, null)
    }
}
