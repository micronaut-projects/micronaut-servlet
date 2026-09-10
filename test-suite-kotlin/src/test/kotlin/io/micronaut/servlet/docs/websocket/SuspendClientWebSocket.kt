package io.micronaut.servlet.docs.websocket

import io.micronaut.context.annotation.Requires
import io.micronaut.websocket.annotation.ClientWebSocket
import io.micronaut.websocket.annotation.OnMessage
import java.util.concurrent.ConcurrentLinkedQueue

@Requires(property = "spec.name", value = "SuspendWebSocketTest")
@ClientWebSocket("/ws/suspend/{username}")
abstract class SuspendClientWebSocket : AutoCloseable {

    val replies: MutableCollection<String> = ConcurrentLinkedQueue()

    @OnMessage
    fun onMessage(message: String) {
        replies.add(message)
    }

    abstract fun send(message: String)
}
