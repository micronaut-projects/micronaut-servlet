package io.micronaut.servlet.jetty

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import org.eclipse.jetty.server.Server
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths


@MicronautTest
class JettyAccessLogSpec extends Specification implements TestPropertyProvider {

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

        when:
        httpClient.toBlocking().exchange(HttpRequest.GET('/test'), String)

        then:
        def exception = thrown(HttpClientResponseException)
        exception.code() == 404

        when:
        Path logPath = Paths.get("logs/test-access.log")
        List<String> lines = Files.readAllLines(logPath)

        then:
        !lines.isEmpty()
        lines.get(0).contains("\"GET /test HTTP/1.1\" 404")
    }

    @Override
    Map<String, String> getProperties() {
        return [
                "spec.name": "JettyAccessLogSpec",
                "micronaut.server.jetty.access-log.enabled": true,
        ]
    }
}
