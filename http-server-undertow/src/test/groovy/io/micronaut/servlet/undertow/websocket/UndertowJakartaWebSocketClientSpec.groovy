package io.micronaut.servlet.undertow.websocket

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.servlet.undertow.websocket.jakarta.JakartaEchoClientEndpoint
import io.micronaut.servlet.undertow.websocket.jakarta.JakartaEchoEndpoint
import io.micronaut.servlet.websocket.MicronautWebSocketContainer
import jakarta.websocket.Session
import jakarta.websocket.WebSocketContainer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class UndertowJakartaWebSocketClientSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'UndertowJakartaWebSocketSpec'])

    PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)

    void "a @ClientEndpoint connects through the container Undertow registered and talks to a @ServerEndpoint"() {
        given:
        WebSocketContainer container = embeddedServer.applicationContext.getBean(WebSocketContainer)
        int peersBefore = JakartaEchoEndpoint.PEERS.size()
        JakartaEchoClientEndpoint client = new JakartaEchoClientEndpoint()

        expect:
        container instanceof MicronautWebSocketContainer

        when:
        Session session = container.connectToServer(client, URI.create("ws://localhost:${embeddedServer.port}/jakarta/echo/football"))

        then: "@OnOpen ran before connectToServer returned, and the subprotocol was negotiated on the wire"
        session.open
        client.session.is(session)
        client.negotiatedSubprotocol == "echo"
        conditions.eventually {
            client.replies.contains("joined football")
            JakartaEchoEndpoint.PEERS.size() == peersBefore + 1
        }

        when:
        client.send("hi")

        then:
        conditions.eventually {
            client.replies.contains("football: hi")
        }

        when:
        session.close()

        then:
        conditions.eventually {
            client.closeReason != null
            JakartaEchoEndpoint.PEERS.size() == peersBefore
        }
        client.closeReason.closeCode.code == 1000
    }
}
