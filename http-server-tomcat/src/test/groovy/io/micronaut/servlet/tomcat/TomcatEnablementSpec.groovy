package io.micronaut.servlet.tomcat

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import org.apache.catalina.startup.Tomcat
import spock.lang.Specification

class TomcatEnablementSpec extends Specification {

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
