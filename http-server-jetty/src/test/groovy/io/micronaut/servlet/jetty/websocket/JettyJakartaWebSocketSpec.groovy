package io.micronaut.servlet.jetty.websocket

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.servlet.jetty.websocket.jakarta.JakartaCodecEndpoint
import io.micronaut.servlet.jetty.websocket.jakarta.JakartaEchoClient
import io.micronaut.servlet.jetty.websocket.jakarta.JakartaEchoEndpoint
import io.micronaut.servlet.jetty.websocket.jakarta.JakartaSharedEndpoint
import io.micronaut.servlet.jetty.websocket.jakarta.JakartaUpgradeFilter
import io.micronaut.websocket.WebSocketClient
import io.micronaut.websocket.exceptions.WebSocketClientException
import reactor.core.publisher.Flux
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class JettyJakartaWebSocketSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'JettyJakartaWebSocketSpec'])

    @Shared
    @AutoCleanup
    WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.URI)

    PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)

    void "a @ServerEndpoint is served through the Micronaut pipeline with one instance per connection"() {
        given:
        int instancesBefore = JakartaEchoEndpoint.INSTANCES.get()
        int filteredBefore = JakartaUpgradeFilter.INVOCATIONS.get()

        when: "two peers connect"
        JakartaEchoClient fred = Flux.from(wsClient.connect(JakartaEchoClient, "/jakarta/echo/football")).blockFirst()
        JakartaEchoClient bob = Flux.from(wsClient.connect(JakartaEchoClient, "/jakarta/echo/football")).blockFirst()

        then: "@OnOpen ran with the path parameter and the raw session"
        conditions.eventually {
            fred.replies.contains("joined football")
            bob.replies.contains("joined football")
        }
        JakartaEchoEndpoint.INSTANCES.get() == instancesBefore + 2
        JakartaEchoEndpoint.PEERS.size() == 2
        JakartaUpgradeFilter.INVOCATIONS.get() == filteredBefore + 2
        !JakartaEchoEndpoint.contextLeaked

        when: "a text message is answered with the handler's return value"
        fred.send("hi")

        then:
        conditions.eventually {
            fred.replies.contains("football: hi")
        }
        !bob.replies.contains("football: hi")

        when: "a binary message goes to the binary handler, which sends through the container session"
        fred.send("some bytes".bytes)

        then:
        conditions.eventually {
            fred.replies.contains("some bytes")
        }

        when: "the server pings and the client's pong reaches the pong handler"
        fred.send("ping".bytes)

        then:
        conditions.eventually {
            JakartaEchoEndpoint.lastPong == "pong-data"
        }

        when: "a handler fails, @OnError sees the cause and the connection stays open"
        fred.send("fail")

        then:
        conditions.eventually {
            JakartaEchoEndpoint.lastError instanceof IllegalStateException
        }
        fred.session.open

        when: "a peer leaves"
        fred.close()

        then: "@OnClose received the Jakarta close reason and the session"
        conditions.eventually {
            JakartaEchoEndpoint.PEERS.size() == 1
            JakartaEchoEndpoint.lastClose != null
        }
        JakartaEchoEndpoint.lastClose.closeCode.code == 1000

        cleanup:
        bob.close()
    }

    void "the filter chain can reject the handshake"() {
        when:
        Flux.from(wsClient.connect(JakartaEchoClient, "/jakarta/echo/private")).blockFirst()

        then:
        def e = thrown(WebSocketClientException)
        e.message.contains("401")
    }

    void "decoders, encoders and the configurator resolve without reflection"() {
        when:
        JakartaEchoClient client = Flux.from(wsClient.connect(JakartaEchoClient, "/jakarta/codec")).blockFirst()

        then: "the configurator's handshake hook ran and its user property reached @OnOpen"
        conditions.eventually {
            client.replies.contains("hello from the configurator")
        }
        JakartaCodecEndpoint.decoderInitialized

        when: "the message is decoded for the handler and its return value encoded"
        client.send("shout hello")

        then:
        conditions.eventually {
            client.replies.contains("reply:HELLO")
        }

        when: "the connection closes and the codecs are destroyed"
        client.close()

        then:
        conditions.eventually {
            JakartaCodecEndpoint.decoderDestroyed
        }
    }

    void "a declared scope wins over the per-connection default"() {
        when:
        JakartaEchoClient one = Flux.from(wsClient.connect(JakartaEchoClient, "/jakarta/shared")).blockFirst()
        JakartaEchoClient two = Flux.from(wsClient.connect(JakartaEchoClient, "/jakarta/shared")).blockFirst()
        one.send("a")
        two.send("b")

        then: "both connections share the one instance and its counter"
        conditions.eventually {
            one.replies.contains("message 1 on /jakarta/shared") || two.replies.contains("message 1 on /jakarta/shared")
            one.replies.contains("message 2 on /jakarta/shared") || two.replies.contains("message 2 on /jakarta/shared")
        }
        JakartaSharedEndpoint.INSTANCES.get() == 1

        cleanup:
        one.close()
        two.close()
    }

    void "a plain codec class is instantiated reflectively only when the application allows it"() {
        when: "nothing allows the type"
        JakartaEchoClient client = Flux.from(wsClient.connect(JakartaEchoClient, "/jakarta/reflective")).blockFirst()

        then: "the endpoint cannot be opened and the connection is closed"
        conditions.eventually {
            !client.session.open
        }

        when: "the package is allowed through core's reflection policy"
        EmbeddedServer allowing = ApplicationContext.run(EmbeddedServer, [
            'spec.name': 'JettyJakartaWebSocketSpec',
            'micronaut.introspection.allow-reflection': ['io.micronaut.servlet.jetty.websocket.jakarta.*']
        ])
        WebSocketClient allowingClient = allowing.applicationContext.createBean(WebSocketClient, allowing.URI)
        JakartaEchoClient allowed = Flux.from(allowingClient.connect(JakartaEchoClient, "/jakarta/reflective")).blockFirst()
        allowed.send("hello")

        then:
        conditions.eventually {
            allowed.replies.contains("HELLO!")
        }

        cleanup:
        allowed?.close()
        allowingClient?.close()
        allowing?.close()
    }
}
