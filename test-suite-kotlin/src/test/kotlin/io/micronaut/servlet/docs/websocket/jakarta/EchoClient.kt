package io.micronaut.servlet.docs.websocket.jakarta

import io.micronaut.context.annotation.Requires
import io.micronaut.websocket.annotation.ClientWebSocket
import io.micronaut.websocket.annotation.OnMessage
import java.util.concurrent.ConcurrentLinkedQueue

@Requires(property = "spec.name", value = "EchoEndpointSpec")
@ClientWebSocket("/ws/echo/{room}")
abstract class EchoClient : AutoCloseable {

    val replies: MutableCollection<String> = ConcurrentLinkedQueue()

    @OnMessage
    fun onMessage(message: String) {
        replies.add(message)
    }

    abstract fun send(message: String)
}
