package io.micronaut.servlet.docs.websocket.jakarta

import io.micronaut.context.annotation.Requires
import jakarta.websocket.OnMessage
import jakarta.websocket.server.PathParam
import jakarta.websocket.server.ServerEndpoint
import kotlinx.coroutines.delay

/**
 * A Jakarta handler that really suspends: its return value is only known once the coroutine
 * completes, and has to be sent then.
 */
@Requires(property = "spec.name", value = "EchoEndpointSpec")
@ServerEndpoint("/ws/suspend-echo/{room}")
class SuspendEchoEndpoint {

    @OnMessage
    suspend fun onMessage(@PathParam("room") room: String, message: String): String {
        delay(50)
        return "[$room] $message"
    }
}
