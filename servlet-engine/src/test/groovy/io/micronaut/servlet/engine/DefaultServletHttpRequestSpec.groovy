package io.micronaut.servlet.engine

import jakarta.servlet.http.HttpServletMapping
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.MappingMatch
import spock.lang.Specification

class DefaultServletHttpRequestSpec extends Specification {

    void "path mapping strips servlet mapping from raw request uri"() {
        given:
        HttpServletMapping mapping = Stub(HttpServletMapping) {
            getMappingMatch() >> MappingMatch.PATH
            getPattern() >> '/app/*'
        }
        HttpServletRequest request = Stub(HttpServletRequest) {
            getHttpServletMapping() >> mapping
            getRequestURI() >> '/app/path%20with%20space'
            getContextPath() >> ''
        }

        expect:
        resolveRequestUri(request) == '/path%20with%20space'
    }

    void "path mapping preserves context path when stripping servlet path"() {
        given:
        HttpServletMapping mapping = Stub(HttpServletMapping) {
            getMappingMatch() >> MappingMatch.PATH
            getPattern() >> '/app/*'
        }
        HttpServletRequest request = Stub(HttpServletRequest) {
            getHttpServletMapping() >> mapping
            getRequestURI() >> '/ctx/app/ping'
            getContextPath() >> '/ctx'
        }

        expect:
        resolveRequestUri(request) == '/ctx/ping'
    }

    void "exact mapping resolves to context root"() {
        given:
        HttpServletMapping mapping = Stub(HttpServletMapping) {
            getMappingMatch() >> MappingMatch.EXACT
        }
        HttpServletRequest request = Stub(HttpServletRequest) {
            getHttpServletMapping() >> mapping
            getRequestURI() >> '/app'
            getContextPath() >> ''
        }

        expect:
        resolveRequestUri(request) == '/'
    }

    void "extension mapping keeps raw request uri"() {
        given:
        HttpServletMapping mapping = Stub(HttpServletMapping) {
            getMappingMatch() >> MappingMatch.EXTENSION
        }
        HttpServletRequest request = Stub(HttpServletRequest) {
            getHttpServletMapping() >> mapping
            getRequestURI() >> '/file%20name.do'
            getContextPath() >> ''
        }

        expect:
        resolveRequestUri(request) == '/file%20name.do'
    }

    private static String resolveRequestUri(HttpServletRequest request) {
        def method = DefaultServletHttpRequest.getDeclaredMethod('resolveRequestUri', HttpServletRequest)
        method.accessible = true
        (String) method.invoke(null, request)
    }
}
