package io.micronaut.servlet.jetty

import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import org.eclipse.jetty.server.Server
import spock.lang.Specification


@MicronautTest
class JettyAccessLogbackConfigSpec extends Specification implements TestPropertyProvider {

    @Inject
    JettyConfiguration.JettyRequestLog requestLog

    @Inject Server server

    @Inject
    @Client("/")
    HttpClient httpClient

    void "test configuration"() {
        expect:
        requestLog.enabled
        server.requestLog != null
        requestLog.resourcePath == "/logback-test-access.xml"
        !requestLog.quiet
        requestLog.fileName == "test123"
    }

    @Override
    Map<String, String> getProperties() {
        return [
                "spec.name": "JettyAccessLogbackSpec",
                "micronaut.server.jetty.access-log.enabled": true,
                "micronaut.server.jetty.access-log.file-name": "test123",
                "micronaut.server.jetty.access-log.resource-path": "/logback-test-access.xml",
                "micronaut.server.jetty.access-log.quiet": false,
        ]
    }
}
