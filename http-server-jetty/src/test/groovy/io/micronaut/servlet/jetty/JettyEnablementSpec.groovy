package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import org.eclipse.jetty.server.Server
import spock.lang.Specification

class JettyEnablementSpec extends Specification {

    void "jetty runtime can be disabled"() {
        given:
        ApplicationContext context = ApplicationContext.builder()
            .deduceEnvironment(false)
            .properties([
                (JettyConfiguration.ENABLED_PROPERTY): false.toString()
            ])
            .start()

        expect:
        context.findBean(Server).empty
        context.findBean(JettyServer).empty
        context.findBean(EmbeddedServer).empty

        cleanup:
        context.close()
    }
}
