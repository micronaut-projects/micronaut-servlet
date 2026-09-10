package io.micronaut.servlet.docs.websocket

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.websocket.CloseReason
import io.micronaut.websocket.WebSocketClient
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * A Kotlin `suspend` handler returns a marker rather than its result once it actually
 * suspends, so its completion has to be picked up from the continuation.
 */
class SuspendWebSocketTest : StringSpec() {

    private val embeddedServer = autoClose(
        ApplicationContext.run(EmbeddedServer::class.java, mapOf("spec.name" to "SuspendWebSocketTest"))
    )

    private val wsClient = autoClose(
        embeddedServer.applicationContext.createBean(WebSocketClient::class.java, embeddedServer.uri)
    )

    private fun connect() =
        Flux.from(wsClient.connect(SuspendClientWebSocket::class.java, "/ws/suspend/ann")).blockFirst()!!

    init {
        "a suspending handler runs to completion and its reply arrives" {
            val client = connect()

            client.send("hello")

            eventually { client.replies.contains("[ann] hello") } shouldBe true
            client.close()
        }

        "a suspending handler that fails after suspending still reaches @OnError" {
            // Without awaiting the continuation the failure would be lost, because the
            // handler returns the suspension marker rather than throwing to the caller.
            val client = connect()

            client.send("boom")

            eventually { client.closeReason != null } shouldBe true
            client.closeReason?.code shouldBe CloseReason.UNSUPPORTED_DATA.code
        }
    }

    private fun eventually(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(100)
        }
        return condition()
    }
}
