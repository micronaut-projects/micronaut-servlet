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

    val replies: MutableCollection<String> = ConcurrentLinkedQueue()
    private lateinit var session: Session

    @OnOpen
    fun onOpen(session: Session) { // <2>
        this.session = session
    }

    @OnMessage
    fun onMessage(message: String) { // <3>
        replies.add(message)
    }

    @OnClose
    fun onClose(reason: CloseReason) {
        // the session is gone; nothing to release
    }

    fun send(message: String) {
        session.basicRemote.sendText(message) // <4>
    }
}
// end::class[]
