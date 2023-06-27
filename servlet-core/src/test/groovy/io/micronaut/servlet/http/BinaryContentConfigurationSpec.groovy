package io.micronaut.servlet.http

import spock.lang.Specification
import spock.lang.Subject

class BinaryContentConfigurationSpec extends Specification {

    @Subject
    BinaryContentConfiguration binaryContentConfiguration = new BinaryContentConfiguration()

    void "#contentType is detected as binary"() {
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
        BinaryContentConfiguration config = new BinaryContentConfiguration()
        config.addBinaryContentType("application/foobar")

        expect:
        config.isBinary("application/foobar")
    }
}
