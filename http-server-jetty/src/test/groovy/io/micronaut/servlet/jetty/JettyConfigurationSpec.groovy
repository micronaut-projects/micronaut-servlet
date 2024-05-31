package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.http.annotation.QueryValue
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.servlet.engine.MicronautServletConfiguration
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.Jetty
import spock.lang.Specification

@MicronautTest
@Property(name = "spec.name", value = SPEC_NAME)
@Property(name = "micronaut.servlet.minThreads", value = "11")
@Property(name = "micronaut.servlet.maxThreads", value = "11")
class JettyConfigurationSpec extends Specification {

    static final String SPEC_NAME = "JettyConfigurationSpec"

    @Inject
    Server jetty

    @Inject
    @Client("/configTest")
    HttpClient client

    void "configuring thread pool is supported"() {
        when:
        var threadPool = jetty.threadPool

        then:
        threadPool.threads == 11

        when:
        def request = HttpRequest.GET("/")
        String response = client.toBlocking().retrieve(request)

        then:
        response == "OK"
    }

    @Controller("/configTest")
    static class TestController {

        @Get
        @Produces(MediaType.TEXT_PLAIN)
        String index() {
            "OK"
        }
    }
}
