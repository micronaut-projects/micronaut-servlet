package io.micronaut.servlet.docs.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.eclipse.jetty.server.Server
import spock.lang.Specification

/**
 * Exercises the listener shown in the Jetty section of the guide, so the documented sources
 * are real, compiled and covered.
 */
@Property(name = "spec.name", value = "JettyServerCustomizerTest")
@MicronautTest
class JettyServerCustomizerSpec extends Specification {

    @Inject
    Server server

    void "the listener customizes the Jetty server"() {
        expect:
        server.stopTimeout == 5000
    }
}
