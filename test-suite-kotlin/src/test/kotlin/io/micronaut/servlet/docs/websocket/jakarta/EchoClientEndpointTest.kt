package io.micronaut.servlet.docs.websocket.jakarta

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import jakarta.websocket.WebSocketContainer
import java.net.URI
import java.time.Duration

class EchoClientEndpointTest : StringSpec() {

    private val embeddedServer = autoClose(
        ApplicationContext.run(EmbeddedServer::class.java, mapOf("spec.name" to "EchoClientEndpointSpec"))
    )

    init {
        "the documented Jakarta client connects through the injected container and talks to the documented endpoint" {
            // tag::connect[]
            val container = embeddedServer.applicationContext.getBean(WebSocketContainer::class.java) // <1>
            val client = EchoClientEndpoint()
            val session = container.connectToServer(client, URI.create("ws://localhost:${embeddedServer.port}/ws/echo/lobby")) // <2>
            // end::connect[]

            session.isOpen shouldBe true
            client.send("Hello!")
            eventually { client.replies.contains("Welcome to lobby") && client.replies.contains("[lobby] Hello!") } shouldBe true

            session.close()
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
