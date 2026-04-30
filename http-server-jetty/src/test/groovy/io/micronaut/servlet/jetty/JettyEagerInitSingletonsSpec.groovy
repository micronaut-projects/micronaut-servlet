package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import spock.lang.Specification

class JettyEagerInitSingletonsSpec extends Specification {

    void "default servlet mapping works without eagerInitSingletons"() {
        expect:
        retrievePing(false) == 'pong'
    }

    void "default servlet mapping works with eagerInitSingletons"() {
        expect:
        retrievePing(true) == 'pong'
    }

    private String retrievePing(boolean eagerInitSingletons) {
        ApplicationContext applicationContext = ApplicationContext.builder(EmbeddedServer)
            .eagerInitSingletons(eagerInitSingletons)
            .properties([
                'micronaut.servlet.mapping': '/app/*',
                'spec.name': 'JettyEagerInitSingletonsSpec',
            ])
            .start()
        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer).start()
        HttpClient httpClient = embeddedServer.applicationContext.createBean(HttpClient, embeddedServer.getURL())
        try {
            return httpClient.toBlocking().retrieve(HttpRequest.GET('/app/ping'))
        } finally {
            httpClient.close()
            applicationContext.close()
        }
    }

    @Requires(property = 'spec.name', value = 'JettyEagerInitSingletonsSpec')
    @Controller
    static class PingController {

        @Get("/ping")
        String ping() {
            "pong"
        }
    }
}
