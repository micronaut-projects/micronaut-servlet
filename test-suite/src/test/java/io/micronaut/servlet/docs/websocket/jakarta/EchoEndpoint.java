package io.micronaut.servlet.docs.websocket.jakarta;

// tag::imports[]
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
// end::imports[]

import io.micronaut.context.annotation.Requires;

@Requires(property = "spec.name", pattern = "EchoEndpointSpec|EchoClientEndpointSpec")
// tag::class[]
@ServerEndpoint("/ws/echo/{room}") // <1>
public class EchoEndpoint {

    private String room;

    @OnOpen
    public void onOpen(Session session, @PathParam("room") String room) throws IOException { // <2>
        this.room = room;
        session.getBasicRemote().sendText("Welcome to " + room); // <3>
    }

    @OnMessage
    public String onMessage(String message) { // <4>
        return "[" + room + "] " + message;
    }

    @OnClose
    public void onClose(CloseReason reason) { // <5>
    }
}
// end::class[]
