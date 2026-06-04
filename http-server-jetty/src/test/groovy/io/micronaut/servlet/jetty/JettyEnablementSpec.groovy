package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.ApplicationConfiguration
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.web.router.Router
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import spock.lang.Specification

class JettyEnablementSpec extends Specification {

    void "jetty configuration exposes enablement toggle"() {
        given:
        def configuration = new JettyConfiguration(null)

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

    void "deprecated jetty server constructor delegates to current constructor"() {
        given:
        def jetty = new Server()
        jetty.addConnector(new ServerConnector(jetty))

        when:
        def server = new JettyServer(
            Stub(ApplicationContext),
            new ApplicationConfiguration(),
            jetty,
            Stub(Router),
            new JettyConfiguration(null),
            []
        )

        then:
        server.getServer().is(jetty)
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
