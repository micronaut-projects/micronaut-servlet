from typing import Annotated

from jakarta.inject import Inject
from java.util.concurrent import TimeUnit
from micronaut.context.annotation import Property
from micronaut.http.client.annotation import Client
from micronaut.servlet.docs.websocket.ChatClientWebSocket import ChatClientWebSocket
from micronaut.test.extensions.junit5.annotation import MicronautTest
from micronaut.websocket import WebSocketClient
from org.junit.jupiter.api import Test
from reactor.core.publisher import Flux


# Exercises the endpoint and client shown in the WebSocket section of the guide, so the
# documented Python sources are real, compiled and covered.
@Property(name="spec.name", value="ChatWebSocketSpec")
@MicronautTest
class ChatWebSocketSpec:
    ws_client: Annotated[WebSocketClient, Inject, Client("/")]

    @Test
    def test_the_documented_chat_endpoint_broadcasts_to_the_other_members_of_a_topic(self):
        fred = Flux.from_(self.ws_client.connect(ChatClientWebSocket, "/ws/chat/football/fred")).blockFirst()
        bob = Flux.from_(self.ws_client.connect(ChatClientWebSocket, {"topic": "football", "username": "bob"})).blockFirst()

        self.await_reply(fred.get_replies(), "[bob] Joined!")

        fred.send("Hello bob!")
        self.await_reply(bob.get_replies(), "[fred] Hello bob!")

        bob.close()
        self.await_reply(fred.get_replies(), "[bob] Disconnected!")

        fred.close()

    def await_reply(self, replies, expected: str) -> None:
        for _ in range(150):
            if replies.contains(expected):
                return
            TimeUnit.MILLISECONDS.sleep(100)
        assert replies.contains(expected)
