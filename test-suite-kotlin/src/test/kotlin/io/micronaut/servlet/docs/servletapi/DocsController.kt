package io.micronaut.servlet.docs.servletapi

// tag::imports[]
import io.micronaut.core.io.Readable
import io.micronaut.core.io.Writable
// end::imports[]
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Part
import io.micronaut.http.annotation.Post
import io.micronaut.http.multipart.CompletedPart
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.BufferedReader

@Requires(property = "spec.name", value = "DocsControllerTest")
@Controller("/docs")
class DocsController {

    // tag::reqrep[]
    @Get("/hello")
    fun process(
        request: HttpServletRequest, // <1>
        response: HttpServletResponse // <2>
    ) {
        response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN)
        response.status = HttpStatus.ACCEPTED.code
        response.writer.use { writer ->
            writer.append("Hello ").append(request.getParameter("name"))
            writer.flush()
        }
    }
    // end::reqrep[]

    // tag::writable[]
    @Post(value = "/writable", processes = ["text/plain"])
    fun readAndWrite(@Body readable: Readable) = Writable { out ->
        BufferedReader(readable.asReader()).use { reader ->
            out.append("Hello ").append(reader.readLine())
        }
    }
    // end::writable[]

    // tag::multipart[]
    @Post(value = "/multipart", consumes = [MediaType.MULTIPART_FORM_DATA], produces = ["text/plain"])
    fun multipart(
        attribute: String, // <1>
        @Part("one") person: Person, // <2>
        @Part("two") text: String, // <3>
        @Part("three") bytes: ByteArray, // <4>
        @Part("four") raw: jakarta.servlet.http.Part, // <5>
        @Part("five") part: CompletedPart // <6>
    ): String = "Ok"
    // end::multipart[]
}
