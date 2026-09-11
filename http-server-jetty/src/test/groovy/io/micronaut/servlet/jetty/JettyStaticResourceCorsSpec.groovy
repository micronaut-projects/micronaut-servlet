package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * Static resources are served through the Micronaut filter chain, so the CORS filter applies to them exactly as it
 * does to controllers and on the Netty server: the configured origin is allowed and any other is refused.
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

    void "an origin that merely starts with the configured one is refused"() {
        when: "an unanchored pattern would find the configured origin inside this one"
        client.toBlocking().exchange(
            HttpRequest.GET('/static/index.txt').header(HttpHeaders.ORIGIN, 'https://example.com.attacker.test'), String)

        then:
        HttpClientResponseException e = thrown()
        e.status == HttpStatus.FORBIDDEN
        e.response.header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) == null
    }

    void "an unrelated origin is refused"() {
        when:
        client.toBlocking().exchange(
            HttpRequest.GET('/static/index.txt').header(HttpHeaders.ORIGIN, 'https://attacker.test'), String)

        then:
        HttpClientResponseException e = thrown()
        e.status == HttpStatus.FORBIDDEN
        e.response.header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) == null
    }
}
