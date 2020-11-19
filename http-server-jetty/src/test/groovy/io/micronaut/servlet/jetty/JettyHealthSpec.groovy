package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.health.HealthStatus
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.management.health.indicator.HealthResult
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import spock.lang.Specification

import javax.inject.Inject

@MicronautTest
@Property(name = "endpoints.all.enabled", value = "true")
@Property(name = "micronaut.application.name", value = "test")
class JettyHealthSpec extends Specification {

    @Client("/")
    @Inject
    RxHttpClient client

    void 'test healthy'() {
        given:
        def liveness = client.exchange("/health/liveness", HealthResult).blockingFirst()
        def readiness = client.exchange("/health/readiness", HealthResult).blockingFirst()
        def overall = client.exchange("/health", HealthResult).blockingFirst()

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
