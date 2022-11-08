package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.health.HealthStatus
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.management.health.indicator.HealthResult
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
@Property(name = "endpoints.all.enabled", value = "true")
@Property(name = "micronaut.application.name", value = "test")
class JettyHealthSpec extends Specification {

    @Client("/")
    @Inject
    HttpClient client

    void 'test healthy'() {
        given:
        def liveness = client.toBlocking().exchange("/health/liveness", Map)
        def readiness = client.toBlocking().exchange("/health/readiness", Map)
        def overall = client.toBlocking().exchange("/health", Map)

        expect:
        liveness.status == HttpStatus.OK
        readiness.status == HttpStatus.OK
        overall.status == HttpStatus.OK
        and:"there are no liveness indicators so unknown"
        liveness.body.get().status == HealthStatus.UNKNOWN as String
        and:"readiness indicates up"
        readiness.body.get().status == HealthStatus.UP as String
        and:'so does overall status'
        overall.body.get().status == HealthStatus.UP as String
    }
}
