package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.health.HealthStatus
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.annotation.Client
import io.micronaut.management.health.indicator.HealthResult
import io.micronaut.reactor.http.client.ReactorHttpClient
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
@Property(name = "endpoints.all.enabled", value = "true")
@Property(name = "micronaut.application.name", value = "test")
class JettyHealthSpec extends Specification {

    @Client("/")
    @Inject
    ReactorHttpClient client

    void 'test healthy'() {
        given:
        def liveness = client.exchange("/health/liveness", HealthResult).blockFirst()
        def readiness = client.exchange("/health/readiness", HealthResult).blockFirst()
        def overall = client.exchange("/health", HealthResult).blockFirst()

        expect:
        liveness.status() == HttpStatus.OK
        readiness.status() == HttpStatus.OK
        overall.status() == HttpStatus.OK
        and:"there are no liveness indicators so unknown"
        liveness.body().status == HealthStatus.UNKNOWN
        and:"readiness indicates up"
        readiness.body().status == HealthStatus.UP
        and:'so does overall status'
        overall.body().status == HealthStatus.UP
    }
}
