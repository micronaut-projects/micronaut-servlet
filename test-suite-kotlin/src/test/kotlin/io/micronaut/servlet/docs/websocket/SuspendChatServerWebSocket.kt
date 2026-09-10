package io.micronaut.servlet.docs.websocket

import io.micronaut.context.annotation.Requires
import io.micronaut.websocket.WebSocketSession
import io.micronaut.websocket.annotation.OnMessage
import io.micronaut.websocket.annotation.ServerWebSocket
import kotlinx.coroutines.delay

/**
 * A handler that really suspends, so the coroutine completion has to be picked up from the
 * continuation rather than read from the immediate return value.
 */
@Requires(property = "spec.name", value = "SuspendWebSocketTest")
@ServerWebSocket("/ws/suspend/{username}")
class SuspendChatServerWebSocket {

    @OnMessage
    suspend fun onMessage(username: String, message: String, session: WebSocketSession) {
        delay(50)
        session.sendSync("[$username] $message")
    }
}
