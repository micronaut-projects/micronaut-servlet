package io.micronaut.servlet.engine.initializer

import io.micronaut.inject.BeanDefinition
import io.micronaut.inject.BeanIdentifier
import jakarta.servlet.annotation.WebFilter
import jakarta.servlet.annotation.WebServlet
import spock.lang.Specification

class MicronautServletInitializerSpec extends Specification {

    void "resolve servlet name prefers WebServlet name over bean identifier"() {
        given:
        BeanIdentifier identifier = Stub(BeanIdentifier) {
            getName() >> 'defaultMicronautServlet'
        }
        BeanDefinition<?> definition = Stub(BeanDefinition) {
            stringValue(WebServlet, 'name') >> Optional.of('micronaut')
        }

        expect:
        resolve('resolveServletName', identifier, definition) == 'micronaut'
    }

    void "resolve filter name prefers WebFilter filterName over bean identifier"() {
        given:
        BeanIdentifier identifier = Stub(BeanIdentifier) {
            getName() >> 'myFilter'
        }
        BeanDefinition<?> definition = Stub(BeanDefinition) {
            stringValue(WebFilter, 'filterName') >> Optional.of('namedFilter')
        }

        expect:
        resolve('resolveFilterName', identifier, definition) == 'namedFilter'
    }

    private static String resolve(String methodName, BeanIdentifier identifier, BeanDefinition<?> definition) {
        def method = MicronautServletInitializer.getDeclaredMethod(methodName, BeanIdentifier, BeanDefinition)
        method.accessible = true
        (String) method.invoke(null, identifier, definition)
    }
}
