package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
class JettyServletAnnotationSpec extends Specification {
    @Inject
    @Client("/")
    HttpClient rxClient

    @Inject MyListener myListener

    void "test extra servlet"() {
        expect:
        rxClient.toBlocking().retrieve("/extra-servlet", String) == 'My Servlet!'
    }

    void "test extra filter"() {
        expect:
        rxClient.toBlocking().retrieve("/extra-filter", String) == 'My Filter!'
    }

    void "test extra listener"() {
        expect:
        myListener.initialized
    }
}
