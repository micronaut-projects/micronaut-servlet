import base64
from typing import Annotated

from jakarta.inject import Inject
from java.lang import String
from java.util import Base64
from micronaut.context.annotation import Property
from micronaut.http import HttpRequest, HttpStatus, MediaType
from micronaut.http.client import HttpClient
from micronaut.http.client.annotation import Client
from micronaut.http.client.multipart import MultipartBody
from micronaut.test.extensions.junit5.annotation import MicronautTest
from org.junit.jupiter.api import Test


def utf8(text: str):
    # a Python bytes object does not select the byte[] overload of addPart, a Java byte[] does
    return Base64.getDecoder().decode(base64.b64encode(text.encode("utf-8")).decode("ascii"))


# Exercises the controller shown in the Servlet API section of the guide, so the documented
# Python sources are real, compiled and covered.
@Property(name="spec.name", value="DocsControllerTest")
@MicronautTest
class DocsControllerTest:
    client: Annotated[HttpClient, Inject, Client("/")]

    @Test
    def test_the_servlet_request_and_response_can_be_injected(self):
        response = self.client.toBlocking().exchange(HttpRequest.GET("/docs/hello?name=Fred"), String)

        assert response.status() == HttpStatus.ACCEPTED
        assert response.body() == "Hello Fred"

    @Test
    def test_readable_and_writable_simplify_io(self):
        body = self.client.toBlocking().retrieve(
            HttpRequest.POST("/docs/writable", "Fred").contentType(MediaType.TEXT_PLAIN), String
        )

        assert body == "Hello Fred"

    @Test
    def test_parts_can_be_injected_with_part(self):
        body = (
            MultipartBody.builder()
            .addPart("attribute", "value")
            .addPart("one", "one.json", MediaType.APPLICATION_JSON_TYPE, utf8('{"name":"Fred","age":20}'))
            .addPart("two", "two.txt", MediaType.TEXT_PLAIN_TYPE, utf8("text"))
            .addPart("three", "three.bin", MediaType.APPLICATION_OCTET_STREAM_TYPE, utf8("bytes"))
            .addPart("four", "four.txt", MediaType.TEXT_PLAIN_TYPE, utf8("raw"))
            .addPart("five", "five.txt", MediaType.TEXT_PLAIN_TYPE, utf8("part"))
            .build()
        )

        result = self.client.toBlocking().retrieve(
            HttpRequest.POST("/docs/multipart", body).contentType(MediaType.MULTIPART_FORM_DATA_TYPE), String
        )

        assert result == "Ok"
