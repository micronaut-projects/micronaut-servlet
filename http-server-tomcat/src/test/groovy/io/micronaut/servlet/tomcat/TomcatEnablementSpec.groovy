package io.micronaut.servlet.tomcat

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import org.apache.catalina.startup.Tomcat
import spock.lang.Specification

class TomcatEnablementSpec extends Specification {

    void "tomcat configuration exposes enablement toggle"() {
        given:
        def configuration = new TomcatConfiguration(null, null)

        expect:
        configuration.enabled
        configuration.protocol == 'org.apache.coyote.http11.Http11NioProtocol'
        configuration.multipartConfiguration.empty
        configuration.accessLogConfiguration.empty
        TomcatConfiguration.PREFIX == 'micronaut.server.tomcat'
        TomcatConfiguration.ENABLED_PROPERTY == 'micronaut.server.tomcat.enabled'

        when:
        configuration.enabled = false
        configuration.protocol = null

        then:
        !configuration.enabled
        configuration.protocol == 'org.apache.coyote.http11.Http11NioProtocol'
    }

    void "tomcat runtime can be disabled"() {
        given:
        ApplicationContext context = ApplicationContext.builder()
            .deduceEnvironment(false)
            .properties([
                (TomcatConfiguration.ENABLED_PROPERTY): false.toString()
            ])
            .start()

        expect:
        context.findBean(Tomcat).empty
        context.findBean(TomcatServer).empty
        context.findBean(EmbeddedServer).empty

        cleanup:
        context.close()
    }
}
