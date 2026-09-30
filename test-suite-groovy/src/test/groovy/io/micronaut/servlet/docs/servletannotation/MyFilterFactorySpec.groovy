package io.micronaut.servlet.docs.servletannotation

import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * Exercises the filter factory shown in the Servlet annotations section of the guide, so the
 * documented sources are real, compiled and covered.
 */
@Property(name = "spec.name", value = "MyFilterFactoryTest")
@Property(name = "my.filter.mapping", value = "/mapped-filter/*")
@MicronautTest
class MyFilterFactorySpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    void "the filter runs for its mappings"() {
        expect:
        client.toBlocking().retrieve("/extra-filter/attribute") == "true"
        client.toBlocking().retrieve("/mapped-filter/attribute") == "true"
    }

    void "the filter does not run for other paths"() {
        expect:
        client.toBlocking().retrieve("/unfiltered/attribute") == "null"
    }
}
