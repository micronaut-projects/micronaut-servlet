package io.micronaut.servlet.docs.websocket

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.websocket.WebSocketClient
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * Exercises the endpoint and client shown in the WebSocket section of the guide, so that the
 * documented Kotlin sources are real, compiled and covered.
 */
class ChatWebSocketTest : StringSpec() {

    private val embeddedServer = autoClose(
        ApplicationContext.run(EmbeddedServer::class.java, mapOf("spec.name" to "ChatWebSocketSpec"))
    )

    private val wsClient = autoClose(
        embeddedServer.applicationContext.createBean(WebSocketClient::class.java, embeddedServer.uri)
    )

    init {
        "the documented chat endpoint broadcasts to the other members of a topic" {
            val fred = Flux.from(wsClient.connect(ChatClientWebSocket::class.java, "/ws/chat/football/fred")).blockFirst()!!
            val bob = Flux.from(
                wsClient.connect(ChatClientWebSocket::class.java, mapOf("topic" to "football", "username" to "bob"))
            ).blockFirst()!!

            eventually { fred.replies.contains("[bob] Joined!") } shouldBe true

            fred.send("Hello bob!")
            eventually { bob.replies.contains("[fred] Hello bob!") } shouldBe true

            bob.close()
            eventually { fred.replies.contains("[bob] Disconnected!") } shouldBe true

            fred.close()
        }
    }

    private fun eventually(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(100)
        }
        return condition()
    }
}
