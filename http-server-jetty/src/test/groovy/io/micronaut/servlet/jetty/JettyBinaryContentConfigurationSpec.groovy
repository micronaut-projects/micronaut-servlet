package io.micronaut.servlet.jetty

import io.micronaut.context.BeanContext
import io.micronaut.servlet.http.BinaryContentConfiguration
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
class JettyBinaryContentConfigurationSpec extends Specification {

    @Inject
    BeanContext beanContext

    void "binary content configuration is available"() {
        expect:
        beanContext.findBean(BinaryContentConfiguration).present
    }

    void "#contentType is detected as binary"() {
        given:
        def binaryContentConfiguration = beanContext.getBean(BinaryContentConfiguration)

        expect:
        binaryContentConfiguration.isBinary(contentType)

        where:
        contentType << [
                "application/octet-stream",
                "image/jpeg",
                "image/png",
                "image/gif",
                "application/zip"
        ]
    }

    void "#contentType is not detected as binary"() {
        given:
        def binaryContentConfiguration = beanContext.getBean(BinaryContentConfiguration)

        expect:
        !binaryContentConfiguration.isBinary(contentType)

        where:
        contentType << [
                "text/plain",
                "text/html",
                "application/json",
                "application/xml",
                "application/x-www-form-urlencoded"
        ]
    }

    void "configuration is modifiable"() {
        given:
        def binaryContentConfiguration = beanContext.getBean(BinaryContentConfiguration)
        binaryContentConfiguration.addBinaryContentType("application/foobar")

        expect:
        binaryContentConfiguration.isBinary("application/foobar")
    }
}
