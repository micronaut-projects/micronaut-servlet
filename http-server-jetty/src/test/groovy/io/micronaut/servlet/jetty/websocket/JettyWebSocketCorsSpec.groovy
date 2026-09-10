package io.micronaut.servlet.jetty.websocket

import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpRequest
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.websocket.WebSocketClient
import io.micronaut.websocket.exceptions.WebSocketClientException
import reactor.core.publisher.Flux
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A browser does not apply its usual CORS response check to a WebSocket handshake, so when
 * CORS is configured the allow-list has to be enforced during the handshake itself.
 * Otherwise a hostile page could open an authenticated socket against the server.
 */
class JettyWebSocketCorsSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
            'spec.name'                                              : 'JettyWebSocketSpec',
            'micronaut.server.cors.enabled'                          : true,
            // core denies a foreign origin to a localhost host before the handshake is
            // reached, so that protection is turned off to exercise the origin check itself
            'micronaut.server.cors.localhost-pass-through'           : true,
            'micronaut.server.cors.configurations.web.allowed-origins': ['https://trusted.example']
    ])

    @Shared
    @AutoCleanup
    WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.URI)

    void "an origin outside the allow-list cannot complete the handshake"() {
        when:
        Flux.from(wsClient.connect(
                ErrorsClientWebSocket,
                HttpRequest.GET("/ws/errors/message-onerror").header("Origin", "https://evil.example")
        )).blockFirst()

        then:
        thrown(WebSocketClientException)
    }

    void "an allowed origin completes the handshake"() {
        when:
        ErrorsClientWebSocket client = Flux.from(wsClient.connect(
                ErrorsClientWebSocket,
                HttpRequest.GET("/ws/errors/message-onerror").header("Origin", "https://trusted.example")
        )).blockFirst()

        then:
        client != null

        cleanup:
        client?.close()
    }

    void "a same-origin handshake is allowed even though it is not in the allow-list"() {
        when:
        // The client sends the server's own origin, which is what a browser does for a
        // same-origin socket. A CORS allow-list is not meant to govern that case.
        ErrorsClientWebSocket client = Flux.from(
                wsClient.connect(ErrorsClientWebSocket, "/ws/errors/message-onerror")
        ).blockFirst()

        then:
        client != null

        cleanup:
        client?.close()
    }
}
