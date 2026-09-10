package io.micronaut.servlet.docs.websocket

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.websocket.WebSocketClient
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * A Kotlin `suspend` handler returns a marker rather than its result once it actually
 * suspends, so its completion has to be picked up from the continuation. Without that the
 * reply would never be sent.
 */
class SuspendWebSocketTest : StringSpec() {

    private val embeddedServer = autoClose(
        ApplicationContext.run(EmbeddedServer::class.java, mapOf("spec.name" to "SuspendWebSocketTest"))
    )

    private val wsClient = autoClose(
        embeddedServer.applicationContext.createBean(WebSocketClient::class.java, embeddedServer.uri)
    )

    init {
        "a suspending @OnMessage handler completes and its reply arrives" {
            val client = Flux.from(
                wsClient.connect(SuspendClientWebSocket::class.java, "/ws/suspend/ann")
            ).blockFirst()!!

            client.send("hello")

            val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
            while (System.nanoTime() < deadline && !client.replies.contains("[ann] hello")) {
                Thread.sleep(100)
            }
            client.replies.contains("[ann] hello") shouldBe true

            client.close()
        }
    }
}
