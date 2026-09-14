package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.runtime.server.EmbeddedServer
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The container sends the Date header, so the framework does not format one per response as well.
 */
class JettyDateHeaderSpec extends Specification {

    void "a response carries exactly one Date header, the container's, by default"() {
        given:
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, ['spec.name': 'JettyDateHeaderSpec'])

        when:
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(server.URI.resolve('/date')).build(), HttpResponse.BodyHandlers.ofString())

        then:
        response.headers().allValues('Date').size() == 1

        cleanup:
        server.close()
    }

    void "a response still carries exactly one Date header when the framework header is enabled explicitly"() {
        given:
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, ['spec.name': 'JettyDateHeaderSpec', 'micronaut.server.date-header': true])

        when:
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(server.URI.resolve('/date')).build(), HttpResponse.BodyHandlers.ofString())

        then:
        response.headers().allValues('Date').size() == 1

        cleanup:
        server.close()
    }

    @Requires(property = 'spec.name', value = 'JettyDateHeaderSpec')
    @Controller('/date')
    static class DateController {
        @Get
        String get() { 'dated' }
    }
}
