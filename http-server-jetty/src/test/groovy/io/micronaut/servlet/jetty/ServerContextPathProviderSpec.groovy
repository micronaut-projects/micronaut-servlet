package io.micronaut.servlet.jetty

import io.micronaut.context.BeanContext
import io.micronaut.http.context.ServerContextPathProvider
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest(startApplication = false)
class ServerContextPathProviderSpec extends Specification {
    @Inject
    BeanContext beanContext

    void "not multiple beans of type ServerContextPathProvider"() {
        when:
        beanContext.getBean(ServerContextPathProvider)

        then:
        noExceptionThrown()
    }
}
