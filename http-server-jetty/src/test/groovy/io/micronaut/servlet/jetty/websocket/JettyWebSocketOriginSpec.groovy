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
import spock.util.concurrent.PollingConditions

/**
 * A `@CrossOrigin` on the endpoint states its own policy, and an explicit
 * `@OnMessage(maxPayloadLength)` outranks the configured message size.
 */
class JettyWebSocketOriginSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
            'spec.name'                                  : 'JettyWebSocketOriginSpec',
            // core denies a foreign origin to a localhost host before the handshake is
            // reached, so that protection is turned off to exercise the origin check itself
            'micronaut.server.cors.localhost-pass-through'           : true,
            'micronaut.servlet.websocket.max-text-message-size': 16
    ])

    @Shared
    @AutoCleanup
    WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.URI)

    void "a route-level @CrossOrigin is enforced even with global CORS disabled"() {
        when:
        Flux.from(wsClient.connect(
                ErrorsClientWebSocket,
                HttpRequest.GET("/cross-origin/chat").header("Origin", "https://evil.example")
        )).blockFirst()

        then:
        thrown(WebSocketClientException)
    }

    void "an origin allowed by @CrossOrigin completes the handshake"() {
        when:
        ErrorsClientWebSocket client = Flux.from(wsClient.connect(
                ErrorsClientWebSocket,
                HttpRequest.GET("/cross-origin/chat").header("Origin", "https://trusted.example")
        )).blockFirst()

        then:
        client != null

        cleanup:
        client?.close()
    }

    void "an explicit maxPayloadLength outranks the configured message size"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)
        String large = "x" * 5000

        when:
        ChatReplyClientWebSocket client = Flux.from(wsClient.connect(ChatReplyClientWebSocket, "/large/chat")).blockFirst()
        client.send(large)

        then: "the message is not cut off by the 16 byte configured limit"
        conditions.eventually {
            client.replies.contains("received 5000")
        }

        cleanup:
        client?.close()
    }
}
