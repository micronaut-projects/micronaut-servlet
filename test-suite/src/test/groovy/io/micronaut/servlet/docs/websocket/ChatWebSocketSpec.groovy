package io.micronaut.servlet.docs.websocket

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.websocket.WebSocketClient
import reactor.core.publisher.Flux
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * Exercises the endpoint and client shown in the WebSocket section of the guide, so the
 * documented sources are real, compiled and covered.
 */
class ChatWebSocketSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'ChatWebSocketSpec'])

    @Shared
    @AutoCleanup
    WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.URI)

    void "the documented chat endpoint broadcasts to the other members of a topic"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)

        when:
        ChatClientWebSocket fred = Flux.from(wsClient.connect(ChatClientWebSocket, "/ws/chat/football/fred")).blockFirst()
        ChatClientWebSocket bob = Flux.from(wsClient.connect(ChatClientWebSocket, [topic: "football", username: "bob"])).blockFirst()

        then:
        conditions.eventually {
            fred.replies.contains("[bob] Joined!")
        }

        when:
        fred.send("Hello bob!")

        then:
        conditions.eventually {
            bob.replies.contains("[fred] Hello bob!")
        }

        when:
        bob.close()

        then:
        conditions.eventually {
            fred.replies.contains("[bob] Disconnected!")
        }

        cleanup:
        fred.close()
    }
}
