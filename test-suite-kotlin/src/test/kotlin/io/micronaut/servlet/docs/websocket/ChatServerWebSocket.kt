package io.micronaut.servlet.docs.websocket

// tag::imports[]
import io.micronaut.websocket.WebSocketBroadcaster
import io.micronaut.websocket.WebSocketSession
import io.micronaut.websocket.annotation.OnClose
import io.micronaut.websocket.annotation.OnMessage
import io.micronaut.websocket.annotation.OnOpen
import io.micronaut.websocket.annotation.ServerWebSocket

import java.util.function.Predicate
// end::imports[]

import io.micronaut.context.annotation.Requires

@Requires(property = "spec.name", value = "ChatWebSocketSpec")
// tag::class[]
@ServerWebSocket("/ws/chat/{topic}/{username}") // <1>
class ChatServerWebSocket(private val broadcaster: WebSocketBroadcaster) {

    @OnOpen // <2>
    fun onOpen(topic: String, username: String, session: WebSocketSession) {
        broadcaster.broadcastSync("[$username] Joined!", isValid(topic, session))
    }

    @OnMessage // <3>
    fun onMessage(topic: String, username: String, message: String, session: WebSocketSession) {
        broadcaster.broadcastSync("[$username] $message", isValid(topic, session)) // <4>
    }

    @OnClose // <5>
    fun onClose(topic: String, username: String, session: WebSocketSession) {
        broadcaster.broadcastSync("[$username] Disconnected!", isValid(topic, session))
    }

    private fun isValid(topic: String, session: WebSocketSession) = Predicate<WebSocketSession> {
        it !== session && topic.equals(it.uriVariables.get("topic", String::class.java).orElse(null), ignoreCase = true)
    }
}
// end::class[]
