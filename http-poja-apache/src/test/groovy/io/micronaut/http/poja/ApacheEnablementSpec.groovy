package io.micronaut.http.poja

import io.micronaut.context.ApplicationContext
import io.micronaut.http.poja.apache.ApacheRuntimeConfiguration
import io.micronaut.http.poja.apache.ApacheServerlessApplication
import io.micronaut.runtime.EmbeddedApplication
import spock.lang.Specification

class ApacheEnablementSpec extends Specification {

    void "apache runtime configuration exposes enablement toggle"() {
        given:
        def configuration = new ApacheRuntimeConfiguration()

        expect:
        configuration.enabled
        ApacheRuntimeConfiguration.PREFIX == 'poja.apache'
        ApacheRuntimeConfiguration.ENABLED_PROPERTY == 'poja.apache.enabled'

        when:
        configuration.enabled = false

        then:
        !configuration.enabled
    }

    void "apache poja runtime can be disabled"() {
        given:
        ApplicationContext context = ApplicationContext.builder()
            .deduceEnvironment(false)
            .properties([
                (ApacheRuntimeConfiguration.ENABLED_PROPERTY): false.toString()
            ])
            .start()

        expect:
        context.findBean(ApacheServerlessApplication).empty
        context.findBean(EmbeddedApplication).empty

        cleanup:
        context.close()
    }
}
