package io.micronaut.servlet.docs.websocket

import io.micronaut.context.annotation.Requires
import io.micronaut.websocket.annotation.ClientWebSocket
import io.micronaut.websocket.annotation.OnMessage
import java.util.concurrent.ConcurrentLinkedQueue

@Requires(property = "spec.name", value = "ChatWebSocketSpec")
// tag::class[]
@ClientWebSocket("/ws/chat/{topic}/{username}") // <1>
abstract class ChatClientWebSocket : AutoCloseable { // <2>

    val replies: MutableCollection<String> = ConcurrentLinkedQueue()

    @OnMessage
    fun onMessage(message: String) {
        replies.add(message) // <3>
    }

    abstract fun send(message: String) // <4>
}
// end::class[]
