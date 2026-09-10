package io.micronaut.servlet.docs.websocket

import io.micronaut.context.annotation.Requires
import io.micronaut.websocket.CloseReason
import io.micronaut.websocket.WebSocketSession
import io.micronaut.websocket.annotation.OnError
import io.micronaut.websocket.annotation.OnMessage
import io.micronaut.websocket.annotation.ServerWebSocket
import kotlinx.coroutines.delay

/**
 * A handler that really suspends, so that its completion has to be picked up from the
 * continuation rather than read from the immediate return value.
 */
@Requires(property = "spec.name", value = "SuspendWebSocketTest")
@ServerWebSocket("/ws/suspend/{username}")
class SuspendChatServerWebSocket {

    @OnMessage
    suspend fun onMessage(username: String, message: String, session: WebSocketSession) {
        delay(50)
        if (message == "boom") {
            // Thrown after the coroutine has suspended, so it only reaches @OnError if the
            // completion is awaited through the continuation.
            throw IllegalStateException("suspended failure")
        }
        session.sendAsync("[$username] $message")
    }

    @OnError
    fun onError(error: Throwable, session: WebSocketSession) {
        session.close(CloseReason(CloseReason.UNSUPPORTED_DATA.code, error.message ?: "error"))
    }
}
