package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import org.eclipse.jetty.server.Server
import spock.lang.Specification

class JettyEnablementSpec extends Specification {

    void "jetty configuration exposes enablement toggle"() {
        given:
        def configuration = new JettyConfiguration(null, null)

        expect:
        configuration.enabled
        configuration.multipartConfiguration.empty
        configuration.requestLog.empty
        configuration.initParameters.isEmpty()
        JettyConfiguration.PREFIX == 'micronaut.server.jetty'
        JettyConfiguration.ENABLED_PROPERTY == 'micronaut.server.jetty.enabled'

        when:
        configuration.enabled = false
        configuration.initParameters = ["name": "value"]

        then:
        !configuration.enabled
        configuration.initParameters == ["name": "value"]
    }

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
