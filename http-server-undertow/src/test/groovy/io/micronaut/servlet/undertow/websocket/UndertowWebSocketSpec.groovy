package io.micronaut.servlet.undertow.websocket

import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.websocket.CloseReason
import io.micronaut.websocket.WebSocketClient
import io.micronaut.websocket.exceptions.WebSocketClientException
import reactor.core.publisher.Flux
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class UndertowWebSocketSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'UndertowWebSocketSpec'])

    @Shared
    @AutoCleanup
    WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.URI)

    @Shared
    @AutoCleanup
    HttpClient httpClient = embeddedServer.applicationContext.createBean(HttpClient, embeddedServer.URL)

    void "the server container is bootstrapped"() {
        expect:
        embeddedServer.applicationContext.containsBean(io.micronaut.servlet.http.websocket.ServletWebSocketUpgrader)
    }

    void "text messages, path variables and the broadcaster"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)

        when:
        ChatClientWebSocket fred = Flux.from(wsClient.connect(ChatClientWebSocket, "/chat/football/fred")).blockFirst()
        ChatClientWebSocket bob = Flux.from(wsClient.connect(ChatClientWebSocket, [topic: "football", username: "bob"])).blockFirst()

        then:
        fred.topic == "football"
        fred.username == "fred"
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
        bob.send("Hi fred. How are things?")

        then:
        conditions.eventually {
            fred.replies.contains("[bob] Hi fred. How are things?")
        }

        cleanup:
        fred.close()
        bob.close()
    }

    void "POJO messages are encoded and decoded as JSON"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)

        when:
        PojoChatClientWebSocket client = Flux.from(wsClient.connect(PojoChatClientWebSocket, "/pojo/chat/games/ann")).blockFirst()
        client.send(new Message("Hello"))

        then:
        conditions.eventually {
            client.replies.contains(new Message("[ann] Hello"))
        }

        cleanup:
        client.close()
    }

    void "binary messages and ping/pong"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)
        BinaryChatServerWebSocket server = embeddedServer.applicationContext.getBean(BinaryChatServerWebSocket)

        when:
        BinaryChatClientWebSocket client = Flux.from(wsClient.connect(BinaryChatClientWebSocket, "/binary/chat/carl")).blockFirst()
        client.send("Hello!".bytes)

        then:
        conditions.eventually {
            client.replies.contains("[carl] Hello!")
        }

        when: "the server sends a ping, the peer answers with a pong"
        client.send("ping".bytes)

        then:
        conditions.eventually {
            server.lastPong == "pong-data"
        }

        cleanup:
        client.close()
    }

    void "@OnOpen binds query values, headers and the originating request"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)
        QueryParamServerWebSocket server = embeddedServer.applicationContext.getBean(QueryParamServerWebSocket)

        when:
        ErrorsClientWebSocket client = Flux.from(wsClient.connect(
                ErrorsClientWebSocket,
                HttpRequest.GET("/charity?dinner=chicken").header("X-Guest", "ann")
        )).blockFirst()

        then:
        conditions.eventually {
            server.dinner == "chicken"
            server.customHeader == "ann"
        }

        cleanup:
        client.close()
    }

    void "a filter that returns a response cancels the upgrade"() {
        when:
        Flux.from(wsClient.connect(ErrorsClientWebSocket, "/secured/chat")).blockFirst()

        then:
        thrown(WebSocketClientException)
    }

    void "a non-upgrade GET to a websocket route is a 400"() {
        when:
        httpClient.toBlocking().exchange(HttpRequest.GET("/chat/football/fred"), String)

        then:
        HttpClientResponseException e = thrown()
        e.status == HttpStatus.BAD_REQUEST
    }

    void "an unmatched upgrade request is refused"() {
        when:
        Flux.from(wsClient.connect(ErrorsClientWebSocket, "/does/not/exist")).blockFirst()

        then:
        thrown(WebSocketClientException)
    }

    void "@OnError handles a failing @OnMessage"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)

        when:
        ErrorsClientWebSocket client = Flux.from(wsClient.connect(ErrorsClientWebSocket, "/ws/errors/message-onerror")).blockFirst()
        client.send("hello")

        then:
        conditions.eventually {
            client.closeReason != null
            client.closeReason.code == CloseReason.UNSUPPORTED_DATA.code
        }
    }

    void "a failing @OnMessage without @OnError closes the connection"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)

        when:
        ErrorsClientWebSocket client = Flux.from(wsClient.connect(ErrorsClientWebSocket, "/ws/errors/message")).blockFirst()
        client.send("hello")

        then:
        conditions.eventually {
            client.closeReason != null
        }
    }
}
