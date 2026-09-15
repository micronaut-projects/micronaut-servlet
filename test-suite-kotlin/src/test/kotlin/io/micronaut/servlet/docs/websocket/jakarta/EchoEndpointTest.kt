package io.micronaut.servlet.docs.websocket.jakarta

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.websocket.WebSocketClient
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * Exercises the Jakarta endpoint shown in the WebSocket section of the guide, so the
 * documented Kotlin source is real, compiled through kapt with the servlet processor, and covered.
 */
class EchoEndpointTest : StringSpec() {

    private val embeddedServer = autoClose(
        ApplicationContext.run(EmbeddedServer::class.java, mapOf("spec.name" to "EchoEndpointSpec"))
    )

    private val wsClient = autoClose(
        embeddedServer.applicationContext.createBean(WebSocketClient::class.java, embeddedServer.uri)
    )

    init {
        "the documented Jakarta endpoint greets on open and echoes what its handler returns" {
            val client = Flux.from(wsClient.connect(EchoClient::class.java, "/ws/echo/lobby")).blockFirst()!!

            eventually { client.replies.contains("Welcome to lobby") } shouldBe true

            client.send("Hello!")
            eventually { client.replies.contains("[lobby] Hello!") } shouldBe true

            client.close()
        }

        "a suspending Jakarta handler's return value is sent once the coroutine completes" {
            val client = Flux.from(wsClient.connect(EchoClient::class.java, "/ws/suspend-echo/lobby")).blockFirst()!!

            client.send("Hello!")
            eventually { client.replies.contains("[lobby] Hello!") } shouldBe true

            client.close()
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
