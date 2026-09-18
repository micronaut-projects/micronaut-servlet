from typing import Annotated

from jakarta.inject import Inject
from micronaut.context.annotation import Property
from micronaut.test.extensions.junit5.annotation import MicronautTest
from org.junit.jupiter.api import Test

try:
    from io.undertow import Undertow
except ImportError:  # TODO(python): packages under `io.` other than `io.micronaut` cannot be imported at runtime
    from undertow import Undertow


# Exercises the listener shown in the Undertow section of the guide, so the documented Python
# sources are real, compiled and covered. The test suite runs Jetty by default, so Undertow is
# enabled for this test only.
@Property(name="spec.name", value="UndertowServerCustomizerTest")
@Property(name="micronaut.server.jetty.enabled", value="false")
@Property(name="micronaut.server.undertow.enabled", value="true")
@MicronautTest
class UndertowServerCustomizerTest:
    undertow: Annotated[Undertow, Inject]

    @Test
    def test_the_listener_customizes_the_undertow_server(self):
        assert self.undertow.getWorker().getIoThreadCount() == 2
