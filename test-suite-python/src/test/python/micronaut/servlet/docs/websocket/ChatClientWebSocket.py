from abc import ABC, abstractmethod

from java.lang import AutoCloseable
from java.util import Collection
from java.util.concurrent import ConcurrentLinkedQueue
from micronaut.context.annotation import Requires
from micronaut.websocket.annotation import ClientWebSocket, OnMessage


@Requires(property="spec.name", value="ChatWebSocketSpec")
# tag::class[]
@ClientWebSocket("/ws/chat/{topic}/{username}")  # <1>
class ChatClientWebSocket(ABC, AutoCloseable):  # <2>

    def __init__(self):
        self.replies = ConcurrentLinkedQueue()

    @OnMessage
    def on_message(self, message: str) -> None:
        self.replies.add(message)  # <3>

    @abstractmethod
    def send(self, message: str) -> None:  # <4>
        ...

    def get_replies(self) -> Collection[str]:
        return self.replies
# end::class[]
