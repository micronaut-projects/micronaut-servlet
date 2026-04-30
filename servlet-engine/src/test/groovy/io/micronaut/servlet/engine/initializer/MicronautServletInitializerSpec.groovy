package io.micronaut.servlet.engine.initializer

import io.micronaut.inject.BeanDefinition
import io.micronaut.servlet.engine.DefaultMicronautServlet
import jakarta.servlet.Servlet
import spock.lang.Specification

class MicronautServletInitializerSpec extends Specification {

    void "default micronaut servlet is detected by bean type"() {
        given:
        BeanDefinition<Servlet> definition = Stub(BeanDefinition) {
            getBeanType() >> DefaultMicronautServlet
        }

        expect:
        resolve(definition)
    }

    void "non-micronaut servlet is not detected by bean type"() {
        given:
        BeanDefinition<Servlet> definition = Stub(BeanDefinition) {
            getBeanType() >> Servlet
        }

        expect:
        !resolve(definition)
    }

    private static boolean resolve(BeanDefinition<Servlet> definition) {
        def method = MicronautServletInitializer.getDeclaredMethod('isMicronautServlet', BeanDefinition)
        method.accessible = true
        (boolean) method.invoke(null, definition)
    }
}
