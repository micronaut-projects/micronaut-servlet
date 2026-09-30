package io.micronaut.servlet.http.server

import com.sun.net.httpserver.HttpHandler
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Factory
import io.micronaut.runtime.server.EmbeddedServer
import jakarta.inject.Singleton
import spock.lang.Specification

class HttpServerFactorySpec extends Specification {

    void "starts the JDK HTTP server without servlet engine configuration"() {
        given:
        EmbeddedServer server = null

        when:
        server = ApplicationContext.run(EmbeddedServer, [
            'micronaut.server.port': 0
        ])

        then:
        server.running

        cleanup:
        server?.stop()
    }

    @Factory
    static class TestHttpHandlerFactory {
        @Singleton
        HttpHandler httpHandler() {
            { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.close()
            } as HttpHandler
        }
    }
}
