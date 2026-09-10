package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * Static resources are served outside the Micronaut filter chain, so their CORS handling is configured separately.
 * It must still allow exactly the origins that were configured.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'JettyStaticResourceCorsSpec')
@Property(name = 'micronaut.router.static-resources.default.paths', value = 'classpath:static-cors')
@Property(name = 'micronaut.router.static-resources.default.mapping', value = '/static/**')
@Property(name = 'micronaut.server.cors.enabled', value = 'true')
@Property(name = 'micronaut.server.cors.configurations.web.allowed-origins[0]', value = 'https://example.com')
class JettyStaticResourceCorsSpec extends Specification {

    @Inject
    @Client('/')
    HttpClient client

    void "the configured origin is allowed"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.GET('/static/index.txt').header(HttpHeaders.ORIGIN, 'https://example.com'), String)

        then:
        response.status == HttpStatus.OK
        response.header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) == 'https://example.com'
    }

    void "an origin that merely starts with the configured one is not allowed"() {
        when: "an unanchored pattern would find the configured origin inside this one"
        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.GET('/static/index.txt').header(HttpHeaders.ORIGIN, 'https://example.com.attacker.test'), String)

        then:
        response.header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) == null
    }

    void "an unrelated origin is not allowed"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.GET('/static/index.txt').header(HttpHeaders.ORIGIN, 'https://attacker.test'), String)

        then:
        response.header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) == null
    }
}
