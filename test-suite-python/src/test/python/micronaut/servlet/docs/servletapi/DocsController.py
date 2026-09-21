from typing import Annotated

# tag::imports[]
from micronaut.core.io import Readable, Writable
# end::imports[]
from jakarta.servlet.http import HttpServletRequest, HttpServletResponse
from jakarta.servlet.http import Part as ServletPart
from java.io import BufferedReader, Writer
from micronaut.context.annotation import Requires
from micronaut.http import HttpHeaders, HttpStatus, MediaType
from micronaut.http.annotation import Body, Controller, Get, Part, Post
from micronaut.http.multipart import CompletedPart
from micronaut.servlet.docs.servletapi.Person import Person


@Requires(property="spec.name", value="DocsControllerTest")
@Controller("/docs")
class DocsController:

    # tag::reqrep[]
    @Get("/hello")
    def process(
        self,
        request: HttpServletRequest,  # <1>
        response: HttpServletResponse,  # <2>
    ) -> None:
        response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN)
        response.setStatus(HttpStatus.ACCEPTED.getCode())
        writer = response.getWriter()
        try:
            writer.append("Hello ").append(request.getParameter("name"))
            writer.flush()
        finally:
            writer.close()
    # end::reqrep[]

    # tag::writable[]

    @Post(value="/writable", processes="text/plain")
    def read_and_write(self, readable: Annotated[Readable, Body]) -> Writable:
        def write_to(out: Writer) -> None:
            reader = BufferedReader(readable.asReader())
            try:
                out.append("Hello ").append(reader.readLine())
            finally:
                reader.close()
        return write_to
    # end::writable[]

    # tag::multipart[]
    @Post(value="/multipart", consumes=MediaType.MULTIPART_FORM_DATA, produces="text/plain")
    def multipart(
        self,
        attribute: str,  # <1>
        person: Annotated[Person, Part("one")],  # <2>
        text: Annotated[str, Part("two")],  # <3>
        data: Annotated[bytes, Part("three")],  # <4>
        raw: Annotated[ServletPart, Part("four")],  # <5>
        part: Annotated[CompletedPart, Part("five")],  # <6>
    ) -> str:
        return "Ok"
    # end::multipart[]
