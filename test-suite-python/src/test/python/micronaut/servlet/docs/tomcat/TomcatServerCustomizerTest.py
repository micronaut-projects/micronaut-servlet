from typing import Annotated

from jakarta.inject import Inject
from micronaut.context.annotation import Property
from micronaut.test.extensions.junit5.annotation import MicronautTest
from org.apache.catalina.startup import Tomcat
from org.junit.jupiter.api import Test


# Exercises the listener shown in the Tomcat section of the guide, so the documented Python
# sources are real, compiled and covered. The test suite runs Jetty by default, so Tomcat is
# enabled for this test only.
@Property(name="spec.name", value="TomcatServerCustomizerTest")
@Property(name="micronaut.server.jetty.enabled", value="false")
@Property(name="micronaut.server.tomcat.enabled", value="true")
@MicronautTest
class TomcatServerCustomizerTest:
    tomcat: Annotated[Tomcat, Inject]

    @Test
    def test_the_listener_customizes_the_tomcat_server(self):
        assert self.tomcat.getServer().getUtilityThreads() == 2
