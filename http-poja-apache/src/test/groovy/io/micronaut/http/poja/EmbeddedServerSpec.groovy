package io.micronaut.http.poja


import io.micronaut.runtime.EmbeddedApplication
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
class EmbeddedServerSpec extends Specification {

    @Inject
    EmbeddedApplication<?> application

    void "test server is running"() {
        when:
        true

        then:
        application.isRunning()
    }

}
