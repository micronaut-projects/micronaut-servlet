package io.micronaut.servlet.docs.websocket.jakarta

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import jakarta.websocket.Session
import jakarta.websocket.WebSocketContainer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class EchoClientEndpointSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'EchoClientEndpointSpec'])

    void "the documented Jakarta client connects through the injected container and talks to the documented endpoint"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)
        // tag::connect[]
        WebSocketContainer container = embeddedServer.applicationContext.getBean(WebSocketContainer) // <1>
        EchoClientEndpoint client = new EchoClientEndpoint()
        Session session = container.connectToServer(client, URI.create("ws://localhost:${embeddedServer.port}/ws/echo/lobby")) // <2>
        // end::connect[]

        expect:
        session.open

        when:
        client.send("Hello!")

        then:
        conditions.eventually {
            client.replies.contains("Welcome to lobby")
            client.replies.contains("[lobby] Hello!")
        }

        cleanup:
        session.close()
    }
}
