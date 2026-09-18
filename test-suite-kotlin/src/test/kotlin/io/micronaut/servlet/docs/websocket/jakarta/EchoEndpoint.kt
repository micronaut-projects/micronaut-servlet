package io.micronaut.servlet.docs.websocket.jakarta

// tag::imports[]
import jakarta.websocket.CloseReason
import jakarta.websocket.OnClose
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.PathParam
import jakarta.websocket.server.ServerEndpoint
// end::imports[]

import io.micronaut.context.annotation.Requires

@Requires(property = "spec.name", pattern = "EchoEndpointSpec|EchoClientEndpointSpec")
// tag::class[]
@ServerEndpoint("/ws/echo/{room}") // <1>
class EchoEndpoint {

    private lateinit var room: String

    @OnOpen
    fun onOpen(session: Session, @PathParam("room") room: String) { // <2>
        this.room = room
        session.basicRemote.sendText("Welcome to $room") // <3>
    }

    @OnMessage
    fun onMessage(message: String): String { // <4>
        return "[$room] $message"
    }

    @OnClose
    fun onClose(reason: CloseReason) { // <5>
    }
}
// end::class[]
