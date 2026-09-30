# tag::imports[]
from micronaut.websocket import WebSocketBroadcaster, WebSocketSession
from micronaut.websocket.annotation import OnClose, OnMessage, OnOpen, ServerWebSocket
# end::imports[]

from java.lang import String
from micronaut.context.annotation import Requires


@Requires(property="spec.name", value="ChatWebSocketSpec")
# tag::class[]
@ServerWebSocket("/ws/chat/{topic}/{username}")  # <1>
class ChatServerWebSocket:

    def __init__(self, broadcaster: WebSocketBroadcaster):
        self.broadcaster = broadcaster

    @OnOpen  # <2>
    def on_open(self, topic: str, username: str, session: WebSocketSession) -> None:
        self.broadcaster.broadcastSync("[" + username + "] Joined!", self.is_valid(topic, session))

    @OnMessage  # <3>
    def on_message(self, topic: str, username: str, message: str, session: WebSocketSession) -> None:
        self.broadcaster.broadcastSync("[" + username + "] " + message, self.is_valid(topic, session))  # <4>

    @OnClose  # <5>
    def on_close(self, topic: str, username: str, session: WebSocketSession) -> None:
        self.broadcaster.broadcastSync("[" + username + "] Disconnected!", self.is_valid(topic, session))

    def is_valid(self, topic: str, session: WebSocketSession):
        return lambda s: s != session and topic.lower() == str(s.getUriVariables().get("topic", String, None)).lower()
# end::class[]
