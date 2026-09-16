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

@Requires(property = "spec.name", value = "EchoEndpointSpec")
// tag::class[]
@ServerEndpoint("/ws/echo/{room}") // <1>
class EchoEndpoint {

    private String room

    @OnOpen
    void onOpen(Session session, @PathParam("room") String room) { // <2>
        this.room = room
        session.basicRemote.sendText("Welcome to " + room) // <3>
    }

    @OnMessage
    String onMessage(String message) { // <4>
        "[" + room + "] " + message
    }

    @OnClose
    void onClose(CloseReason reason) { // <5>
    }
}
// end::class[]
