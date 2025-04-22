package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import org.eclipse.jetty.server.Server
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths


class JettyAccessDefaultSpec extends Specification {

    @Shared
    File accessLogDir = new File("logs") // or wherever Jetty expects

    void "test configuration"() {

        given:
        if (!accessLogDir.exists()) {
            accessLogDir.mkdirs()
        }
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, getProperties())
        Server server = embeddedServer.getApplicationContext().getBean(Server)
        JettyConfiguration.JettyRequestLog requestLog = embeddedServer.getApplicationContext().getBean(JettyConfiguration.JettyRequestLog)
        HttpClient httpClient = HttpClient.create(embeddedServer.URL)

        expect:
        requestLog.enabled
        server.requestLog != null

        when:
        httpClient.toBlocking().exchange(HttpRequest.GET('/test'), String)

        then:
        def exception = thrown(HttpClientResponseException)
        exception.code() == 401

        when:
        Path logPath = Paths.get("logs/test-default-access.log")
        List<String> lines = Files.readAllLines(logPath)

        then:
        !lines.isEmpty()
        lines.get(0).contains("\"GET /test HTTP/1.1\" 401")
        !lines.get(0).contains("logback")
    }

    Map<String, String> getProperties() {
        return [

                "spec.name": "JettyAccessDefaultSpec",
                "micronaut.server.jetty.access-log.enabled": true,
                "micronaut.server.jetty.access-log.filename": "logs/test-default-access.log"
        ]
    }
}
