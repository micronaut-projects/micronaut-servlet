package io.micronaut.servlet.undertow

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import io.undertow.Undertow
import spock.lang.Specification

class UndertowEnablementSpec extends Specification {

    void "undertow configuration exposes enablement toggle"() {
        given:
        def configuration = new UndertowConfiguration(null)

        expect:
        configuration.enabled
        UndertowConfiguration.PREFIX == 'micronaut.server.undertow'
        UndertowConfiguration.ENABLED_PROPERTY == 'micronaut.server.undertow.enabled'

        when:
        configuration.enabled = false

        then:
        !configuration.enabled
    }

    void "undertow runtime can be disabled"() {
        given:
        ApplicationContext context = ApplicationContext.builder()
            .deduceEnvironment(false)
            .properties([
                (UndertowConfiguration.ENABLED_PROPERTY): false.toString()
            ])
            .start()

        expect:
        context.findBean(Undertow).empty
        context.findBean(UndertowServer).empty
        context.findBean(EmbeddedServer).empty

        cleanup:
        context.close()
    }
}
