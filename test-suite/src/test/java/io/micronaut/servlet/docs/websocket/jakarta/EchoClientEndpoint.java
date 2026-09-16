package io.micronaut.servlet.docs.websocket.jakarta;

// tag::imports[]
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
// end::imports[]

import io.micronaut.context.annotation.Requires;

@Requires(property = "spec.name", value = "EchoClientEndpointSpec")
// tag::class[]
@ClientEndpoint // <1>
public class EchoClientEndpoint {

    private final Collection<String> replies = new ConcurrentLinkedQueue<>();
    private Session session;

    @OnOpen
    public void onOpen(Session session) { // <2>
        this.session = session;
    }

    @OnMessage
    public void onMessage(String message) { // <3>
        replies.add(message);
    }

    @OnClose
    public void onClose(CloseReason reason) {
    }

    public void send(String message) throws IOException {
        session.getBasicRemote().sendText(message); // <4>
    }

    public Collection<String> getReplies() {
        return replies;
    }
}
// end::class[]
