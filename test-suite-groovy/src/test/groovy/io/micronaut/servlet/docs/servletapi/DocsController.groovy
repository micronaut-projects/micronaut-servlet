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

@Requires(property = "spec.name", value = "DocsControllerTest")
@Controller("/docs")
class DocsController {

    // tag::reqrep[]
    @Get("/hello")
    void process(
            HttpServletRequest request, // <1>
            HttpServletResponse response) { // <2>
        response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN)
        response.setStatus(HttpStatus.ACCEPTED.code)
        response.writer.withCloseable { writer ->
            writer.append("Hello ").append(request.getParameter("name"))
            writer.flush()
        }
    }
    // end::reqrep[]

    // tag::writable[]
    @Post(value = "/writable", processes = "text/plain")
    Writable readAndWrite(@Body Readable readable) {
        return { out ->
            new BufferedReader(readable.asReader()).withCloseable { reader ->
                out.append("Hello ").append(reader.readLine())
            }
        } as Writable
    }
    // end::writable[]

    // tag::multipart[]
    @Post(value = "/multipart", consumes = MediaType.MULTIPART_FORM_DATA, produces = "text/plain")
    String multipart(
            String attribute, // <1>
            @Part("one") Person person, // <2>
            @Part("two") String text, // <3>
            @Part("three") byte[] bytes, // <4>
            @Part("four") jakarta.servlet.http.Part raw, // <5>
            @Part("five") CompletedPart part) { // <6>
        return "Ok"
    }
    // end::multipart[]
}
