package io.micronaut.servlet.docs.websocket.jakarta

// tag::imports[]
import jakarta.websocket.ClientEndpoint
import jakarta.websocket.CloseReason
import jakarta.websocket.OnClose
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session

import java.util.concurrent.ConcurrentLinkedQueue
// end::imports[]

import io.micronaut.context.annotation.Requires

@Requires(property = "spec.name", value = "EchoClientEndpointSpec")
// tag::class[]
@ClientEndpoint // <1>
class EchoClientEndpoint {

    final Collection<String> replies = new ConcurrentLinkedQueue<>()
    private Session session

    @OnOpen
    void onOpen(Session session) { // <2>
        this.session = session
    }

    @OnMessage
    void onMessage(String message) { // <3>
        replies.add(message)
    }

    @OnClose
    void onClose(CloseReason reason) {
    }

    void send(String message) {
        session.basicRemote.sendText(message) // <4>
    }
}
// end::class[]
