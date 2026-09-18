from typing import Annotated

from jakarta.inject import Inject
from micronaut.context.annotation import Property
from micronaut.http.client import HttpClient
from micronaut.http.client.annotation import Client
from micronaut.test.extensions.junit5.annotation import MicronautTest
from org.junit.jupiter.api import Test


# Exercises the filter factory shown in the Servlet annotations section of the guide, so the
# documented Python sources are real, compiled and covered.
@Property(name="spec.name", value="MyFilterFactoryTest")
@Property(name="my.filter.mapping", value="/mapped-filter/*")
@MicronautTest
class MyFilterFactoryTest:
    client: Annotated[HttpClient, Inject, Client("/")]

    @Test
    def test_the_filter_runs_for_its_mappings(self):
        assert self.client.toBlocking().retrieve("/extra-filter/attribute") == "true"
        assert self.client.toBlocking().retrieve("/mapped-filter/attribute") == "true"

    @Test
    def test_the_filter_does_not_run_for_other_paths(self):
        assert self.client.toBlocking().retrieve("/unfiltered/attribute") == "null"
