package io.micronaut.servlet.docs.websocket

import io.micronaut.context.annotation.Requires
import io.micronaut.websocket.CloseReason
import io.micronaut.websocket.annotation.ClientWebSocket
import io.micronaut.websocket.annotation.OnClose
import io.micronaut.websocket.annotation.OnMessage
import java.util.concurrent.ConcurrentLinkedQueue

@Requires(property = "spec.name", value = "SuspendWebSocketTest")
@ClientWebSocket("/ws/suspend/{username}")
abstract class SuspendClientWebSocket : AutoCloseable {

    val replies: MutableCollection<String> = ConcurrentLinkedQueue()

    @Volatile
    var closeReason: CloseReason? = null
        private set

    @OnMessage
    fun onMessage(message: String) {
        replies.add(message)
    }

    @OnClose
    fun onClose(closeReason: CloseReason) {
        this.closeReason = closeReason
    }

    abstract fun send(message: String)
}
