package io.micronaut.servlet.undertow

import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.servlet.undertow.UndertowConfiguration.AccessLogConfiguration
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import io.undertow.Undertow
import io.undertow.server.handlers.accesslog.AccessLogHandler
import jakarta.inject.Inject
import spock.lang.Specification

import java.nio.file.Files

@MicronautTest
class UndertowAccessLogSpec extends Specification implements TestPropertyProvider {
    @Inject AccessLogConfiguration accessLogConfiguration
    @Inject Undertow undertow
    @Inject
    @Client("/")
    HttpClient rxClient

    @Value("\${micronaut.server.undertow.access-log.output-directory}")
    String log

    void 'test access log configuration'() {
        expect:
        accessLogConfiguration
        accessLogConfiguration.pattern == 'combined'
        undertow != null
        undertow.listenerInfo[0].openListener.rootHandler instanceof AccessLogHandler
        rxClient.toBlocking().retrieve("/log-me") == 'ok'
        new File(log, "access-log").exists()
        new File(log, "access-log").text
    }

    @Override
    Map<String, String> getProperties() {
        return [
                'spec.name':'UndertowAccessLogSpec',
                "micronaut.server.undertow.access-log.enabled": true,
                "micronaut.server.undertow.access-log.pattern": "combined",
                "micronaut.server.undertow.access-log.output-directory": Files.createTempDirectory("test-log").toAbsolutePath().toString()
        ]
    }

    @Controller('/log-me')
    @Requires(property = "spec.name", value = 'UndertowAccessLogSpec')
    static class LogTestController {
        @Get(produces = "text/plain")
        String go() {
            return "ok";
        }
    }
}
