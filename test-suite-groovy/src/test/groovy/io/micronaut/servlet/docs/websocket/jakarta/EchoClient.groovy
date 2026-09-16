package io.micronaut.servlet.docs.websocket.jakarta

import io.micronaut.context.annotation.Requires
import io.micronaut.websocket.annotation.ClientWebSocket
import io.micronaut.websocket.annotation.OnMessage

import java.util.concurrent.ConcurrentLinkedQueue

@Requires(property = "spec.name", value = "EchoEndpointSpec")
@ClientWebSocket("/ws/echo/{room}")
abstract class EchoClient implements AutoCloseable {

    final Collection<String> replies = new ConcurrentLinkedQueue<>()

    @OnMessage
    void onMessage(String message) {
        replies.add(message)
    }

    abstract void send(String message)
}
