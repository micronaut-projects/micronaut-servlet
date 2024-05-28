package io.micronaut.servlet.tomcat

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import org.apache.catalina.Valve
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.valves.Constants
import spock.lang.Specification

import java.nio.file.Files

@MicronautTest
class TomcatAccessLogSpec extends Specification implements TestPropertyProvider {
    @Inject TomcatConfiguration.AccessLogConfiguration accessLogConfiguration
    @Inject Tomcat tomcat

    void "test access log"() {
        expect:
        accessLogConfiguration.enabled
        accessLogConfiguration.pattern == Constants.AccessLog.COMBINED_PATTERN
        def valves = tomcat.host.findChildren().first().pipeline.valves
        valves
        valves.first() instanceof TomcatConfiguration.AccessLogConfiguration
    }

    @Override
    Map<String, String> getProperties() {
        return [
                "micronaut.server.tomcat.access-log.enabled": true,
                "micronaut.server.tomcat.access-log.pattern": Constants.AccessLog.COMBINED_PATTERN
        ]
    }
}
