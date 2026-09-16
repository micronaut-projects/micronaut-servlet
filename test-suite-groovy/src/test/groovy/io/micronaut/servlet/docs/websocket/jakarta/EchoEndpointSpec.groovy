package io.micronaut.servlet.docs.websocket.jakarta

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.websocket.WebSocketClient
import reactor.core.publisher.Flux
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * Exercises the Jakarta endpoint shown in the WebSocket section of the guide, so the
 * documented source is real, compiled and covered.
 */
class EchoEndpointSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'EchoEndpointSpec'])

    @Shared
    @AutoCleanup
    WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.URI)

    void "the documented Jakarta endpoint greets on open and echoes what its handler returns"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)

        when:
        EchoClient client = Flux.from(wsClient.connect(EchoClient, "/ws/echo/lobby")).blockFirst()

        then:
        conditions.eventually {
            client.replies.contains("Welcome to lobby")
        }

        when:
        client.send("Hello!")

        then:
        conditions.eventually {
            client.replies.contains("[lobby] Hello!")
        }

        cleanup:
        client.close()
    }
}

