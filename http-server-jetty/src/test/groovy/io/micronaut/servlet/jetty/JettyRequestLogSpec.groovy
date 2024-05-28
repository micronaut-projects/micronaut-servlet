package io.micronaut.servlet.jetty

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import org.eclipse.jetty.server.CustomRequestLog
import org.eclipse.jetty.server.Server
import spock.lang.Specification

import java.nio.file.Files

@MicronautTest
class JettyRequestLogSpec extends Specification implements TestPropertyProvider{
    @Inject
    JettyConfiguration.JettyRequestLog requestLog

    @Inject Server server

    void "test configuration"() {
        expect:
        requestLog.enabled
        requestLog.requestLogWriter.retainDays == 10
        requestLog.requestLogWriter.fileName != null
        requestLog.pattern == CustomRequestLog.NCSA_FORMAT
        server.requestLog != null
    }

    @Override
    Map<String, String> getProperties() {
        return [
                "micronaut.server.jetty.access-log.enabled": true,
                "micronaut.server.jetty.access-log.filename": Files.createTempFile('log', 'test').toAbsolutePath().toString(),
                "micronaut.server.jetty.access-log.retain-days": 10,
                "micronaut.server.jetty.access-log.pattern": CustomRequestLog.NCSA_FORMAT
        ]
    }
}
