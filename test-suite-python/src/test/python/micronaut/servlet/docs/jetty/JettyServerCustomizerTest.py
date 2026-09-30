from typing import Annotated

from jakarta.inject import Inject
from micronaut.context.annotation import Property
from micronaut.test.extensions.junit5.annotation import MicronautTest
from org.eclipse.jetty.server import Server
from org.junit.jupiter.api import Test


# Exercises the listener shown in the Jetty section of the guide, so the documented Python
# sources are real, compiled and covered.
@Property(name="spec.name", value="JettyServerCustomizerTest")
@MicronautTest
class JettyServerCustomizerTest:
    server: Annotated[Server, Inject]

    @Test
    def test_the_listener_customizes_the_jetty_server(self):
        assert self.server.getStopTimeout() == 5000
