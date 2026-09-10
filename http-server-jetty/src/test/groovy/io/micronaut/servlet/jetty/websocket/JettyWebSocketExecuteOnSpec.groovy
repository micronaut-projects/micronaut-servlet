package io.micronaut.servlet.jetty.websocket

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.websocket.WebSocketClient
import reactor.core.publisher.Flux
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * On a servlet container the request thread is already safe to block on, so Micronaut
 * skips redispatch by default and ExecuteOn handlers run inline. Turning that
 * optimisation off proves the executor selector really is consulted for WebSocket
 * handlers, which is what makes ExecuteOn work.
 */
class JettyWebSocketExecuteOnSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
            'spec.name'                                    : 'JettyWebSocketSpec',
            'micronaut.server.redispatch-non-blocking-only': false
    ])

    @Shared
    @AutoCleanup
    WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.URI)

    void "ExecuteOn redispatches websocket handlers to the blocking executor"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)
        ChatServerWebSocket server = embeddedServer.applicationContext.getBean(ChatServerWebSocket)

        when:
        ChatClientWebSocket client = Flux.from(wsClient.connect(ChatClientWebSocket, "/chat/threads/ann")).blockFirst()
        client.send("hello")

        then:
        conditions.eventually {
            server.messageThreadName != null
        }
        server.openThreadName.startsWith("virtual-executor") || server.openThreadName.contains("io-executor")
        server.messageThreadName.startsWith("virtual-executor") || server.messageThreadName.contains("io-executor")

        cleanup:
        client.close()
    }
}
