package io.micronaut.servlet.jetty.websocket

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.servlet.jetty.websocket.jakarta.JakartaEchoClientEndpoint
import io.micronaut.servlet.jetty.websocket.jakarta.JakartaEchoEndpoint
import io.micronaut.servlet.jetty.websocket.jakarta.JakartaFailingClientEndpoint
import io.micronaut.servlet.websocket.MicronautWebSocketContainer
import jakarta.websocket.DeploymentException
import jakarta.websocket.Session
import jakarta.websocket.WebSocketContainer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.nio.ByteBuffer

class JettyJakartaWebSocketClientSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'JettyJakartaWebSocketSpec'])

    @Shared
    WebSocketContainer container = embeddedServer.applicationContext.getBean(WebSocketContainer)

    PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)

    private URI uri(String path) {
        URI.create("ws://localhost:${embeddedServer.port}${path}")
    }

    void "the injected WebSocketContainer is the Micronaut one, backed by the servlet container"() {
        expect:
        container instanceof MicronautWebSocketContainer
        container.defaultMaxTextMessageBufferSize > 0
    }

    void "a @ClientEndpoint talks to a @ServerEndpoint through the compiled metadata on both sides"() {
        given:
        int peersBefore = JakartaEchoEndpoint.PEERS.size()
        JakartaEchoClientEndpoint client = new JakartaEchoClientEndpoint()

        when: "the instance form is used"
        Session session = container.connectToServer(client, uri("/jakarta/echo/football"))

        then: "@OnOpen ran before connectToServer returned, with the container's session and the negotiated subprotocol"
        session.open
        client.session.is(session)
        client.negotiatedSubprotocol == "echo"

        and: "the declared configurator saw the handshake, and its header is bound in the handler"
        client.clientHeader == "jakarta"
        JakartaEchoClientEndpoint.HeaderConfigurator.afterResponse

        and: "the server greeted the new peer"
        conditions.eventually {
            client.replies.contains("joined football")
            JakartaEchoEndpoint.PEERS.size() == peersBefore + 1
        }

        when: "the client sends through its own send method"
        client.send("hi")

        then: "the server's return value reaches the client's text handler"
        conditions.eventually {
            client.replies.contains("football: hi")
        }

        when: "a message the client handler answers with a return value"
        client.send("ack")

        then: "the return value was sent to the server, which echoed it"
        conditions.eventually {
            client.replies.contains("football: client-ack")
        }

        when: "a binary message"
        session.basicRemote.sendBinary(ByteBuffer.wrap("some bytes".bytes))

        then: "the server echoed it to the client's binary handler"
        conditions.eventually {
            client.binaryReplies.contains("some bytes")
        }

        when: "the client pings"
        session.asyncRemote.sendPing(ByteBuffer.wrap("are you there".bytes))

        then: "the server's pong reaches the client's pong handler"
        conditions.eventually {
            client.lastPong == "are you there"
        }

        when: "the client closes"
        client.close()

        then: "@OnClose received the Jakarta close reason and the server saw the peer leave"
        conditions.eventually {
            client.closeReason != null
            JakartaEchoEndpoint.PEERS.size() == peersBefore
        }
        client.closeReason.closeCode.code == 1000
        client.error == null
    }

    void "the class form creates one endpoint instance per connection"() {
        given:
        int instancesBefore = JakartaEchoClientEndpoint.INSTANCES.get()

        when:
        Session one = container.connectToServer(JakartaEchoClientEndpoint, uri("/jakarta/echo/tennis"))
        Session two = container.connectToServer(JakartaEchoClientEndpoint, uri("/jakarta/echo/tennis"))

        then:
        one.open
        two.open
        !one.is(two)
        JakartaEchoClientEndpoint.INSTANCES.get() == instancesBefore + 2

        cleanup:
        one.close()
        two.close()
    }

    void "a @ClientEndpoint talks to a Micronaut @ServerWebSocket"() {
        given:
        JakartaEchoClientEndpoint client = new JakartaEchoClientEndpoint()

        when:
        Session session = container.connectToServer(client, uri("/jakarta/chat/fred"))

        then:
        conditions.eventually {
            client.replies.contains("welcome fred")
        }

        when:
        client.send("hello")

        then:
        conditions.eventually {
            client.replies.contains("fred said hello")
        }

        cleanup:
        session.close()
    }

    void "a failing @OnOpen fails the connection and closes the session"() {
        when:
        container.connectToServer(JakartaFailingClientEndpoint, uri("/jakarta/echo/football"))

        then:
        DeploymentException e = thrown()
        e.cause instanceof IllegalStateException
        e.cause.message == "refusing to open"
        JakartaFailingClientEndpoint.lastError instanceof IllegalStateException
    }

    void "a handshake the filter chain rejects is reported the way the container reports its own failures"() {
        given:
        int instancesBefore = JakartaEchoClientEndpoint.INSTANCES.get()

        when:
        container.connectToServer(JakartaEchoClientEndpoint, uri("/jakarta/echo/private"))

        then: "Jetty reports the refused upgrade as an IOException, as the Jakarta API declares"
        thrown(IOException)
        JakartaEchoClientEndpoint.INSTANCES.get() == instancesBefore + 1
    }
}
