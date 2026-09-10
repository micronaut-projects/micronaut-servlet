package io.micronaut.servlet.engine

import io.micronaut.http.cookie.Cookie
import io.micronaut.http.cookie.SameSite
import spock.lang.Specification

class ServletCookieAdapterSpec extends Specification {

    void "cookies are ordered by name, then path, then domain"() {
        expect:
        adapter("a", "/", null) < adapter("b", "/", null)
        adapter("a", "/aaa", null) < adapter("a", "/bbb", null)
        adapter("a", "/", "aaa.com") < adapter("a", "/", "bbb.com")
        adapter("a", "/", null).compareTo(adapter("a", "/", null)) == 0
    }

    void "a null path or domain orders before one that is set"() {
        expect:
        adapter("a", null, null) < adapter("a", "/", null)
        adapter("a", "/", null) < adapter("a", "/", "example.com")
    }

    void "comparing to a null cookie is rejected"() {
        when:
        adapter("a", "/", null).compareTo(null)

        then:
        thrown(NullPointerException)
    }

    void "cookies are equal on name, path and domain, with the domain case insensitive"() {
        given:
        def cookie = adapter("session", "/", "Example.com")

        expect:
        cookie == adapter("session", "/", "example.COM")
        cookie.hashCode() == adapter("session", "/", "example.com").hashCode()
        cookie != adapter("other", "/", "example.com")
        cookie != adapter("session", "/other", "example.com")
        cookie != "not a cookie"
    }

    void "a value change does not change identity"() {
        given:
        def cookie = adapter("session", "/", null)

        expect:
        cookie.value("changed") == adapter("session", "/", null)
    }

    void "SameSite survives a round trip through the servlet cookie"() {
        given:
        def cookie = adapter("session", "/", null)

        expect:
        cookie.getSameSite().empty

        when:
        cookie.sameSite(SameSite.Strict)

        then:
        cookie.getSameSite().get() == SameSite.Strict
        cookie.cookie.getAttribute(Cookie.ATTRIBUTE_SAME_SITE) == "Strict"

        when:
        cookie.sameSite(null)

        then:
        cookie.getSameSite().empty
    }

    void "an unrecognised SameSite value reads back as absent"() {
        given:
        def cookie = adapter("session", "/", null)
        cookie.cookie.setAttribute(Cookie.ATTRIBUTE_SAME_SITE, "sideways")

        expect:
        cookie.getSameSite().empty
    }

    void "the adapter reads through to the servlet cookie"() {
        given:
        def servletCookie = new jakarta.servlet.http.Cookie("session", "abc")
        servletCookie.path = "/app"
        servletCookie.domain = "example.com"
        servletCookie.maxAge = 60
        servletCookie.secure = true
        servletCookie.httpOnly = true
        def cookie = new ServletCookieAdapter(servletCookie)

        expect:
        cookie.name == "session"
        cookie.value == "abc"
        cookie.path == "/app"
        cookie.domain == "example.com"
        cookie.maxAge == 60L
        cookie.secure
        cookie.httpOnly
        cookie.cookie.is(servletCookie)
    }

    void "the adapter writes through to the servlet cookie"() {
        given:
        def servletCookie = new jakarta.servlet.http.Cookie("session", "abc")
        def cookie = new ServletCookieAdapter(servletCookie)

        when:
        cookie.value("changed")
            .domain("other.com")
            .path("/elsewhere")
            .maxAge(120L)
            .secure(true)
            .httpOnly(true)

        then:
        servletCookie.value == "changed"
        servletCookie.domain == "other.com"
        servletCookie.path == "/elsewhere"
        servletCookie.maxAge == 120
        servletCookie.secure
        servletCookie.httpOnly
    }

    private static ServletCookieAdapter adapter(String name, String path, String domain) {
        def servletCookie = new jakarta.servlet.http.Cookie(name, "value")
        if (path != null) {
            servletCookie.path = path
        }
        if (domain != null) {
            servletCookie.domain = domain
        }
        new ServletCookieAdapter(servletCookie)
    }
}
