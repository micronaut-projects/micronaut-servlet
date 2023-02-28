
package io.micronaut.servlet.tomcat;

import io.micronaut.core.io.IOUtils;
import io.micronaut.core.io.Readable;
import io.micronaut.core.io.Writable;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.http.cookie.Cookie;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;

@Controller("/parameters")
public class ParametersController {

    @Get(value = "/uri/{name}", produces = MediaType.TEXT_PLAIN)
    String uriParam(String name) {
        return "Hello " + name;
    }

    @Get(value = "/query", produces = MediaType.TEXT_PLAIN)
    String queryValue(@QueryValue("q") String name) {
        return "Hello " + name;
    }

    @Get(value = "/allParams", produces = MediaType.TEXT_PLAIN)
    String allParams(HttpParameters parameters) {
        return "Hello " + parameters.get("name") + " " + parameters.get("age", int.class).orElse(null);
    }

    @Get(value = "/header", produces = MediaType.TEXT_PLAIN)
    String headerValue(@Header(HttpHeaders.CONTENT_TYPE) String contentType) {
        return "Hello " + contentType;
    }

    @Get("/cookies")
    io.micronaut.http.HttpResponse<String> cookies(@CookieValue String myCookie) {
        return io.micronaut.http.HttpResponse.ok(myCookie)
                .cookie(Cookie.of("foo", "bar").httpOnly(true).domain("http://foo.com"));
    }

    @Get("/reqAndRes")
    void requestAndResponse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN);
        response.setStatus(HttpStatus.ACCEPTED.getCode());
        try (final PrintWriter writer = response.getWriter()) {
            writer.append("Good");
            writer.flush();
        }
    }

    @Post(value = "/stringBody", processes = MediaType.TEXT_PLAIN)
    String stringBody(@Body String body) {
        return "Hello " + body;
    }

    @Post(value = "/bytesBody",processes = MediaType.TEXT_PLAIN)
    String bytesBody(@Body byte[] body) {
        return "Hello " + new String(body);
    }

    @Post(value = "/jsonBody", processes = MediaType.APPLICATION_JSON)
    Person jsonBody(@Body Person body) {
        return body;
    }

    @Post(value = "/jsonBodySpread", processes = MediaType.APPLICATION_JSON)
    Person jsonBody(String name, int age) {
        return new Person(name, age);
    }

    @Post(value = "/fullRequest", processes = MediaType.APPLICATION_JSON)
    io.micronaut.http.HttpResponse<Person> fullReq(io.micronaut.http.HttpRequest<Person> request) {
        final Person person = request.getBody().orElseThrow(() -> new RuntimeException("No body"));
        final MutableHttpResponse<Person> response = io.micronaut.http.HttpResponse.ok(person);
        response.header("Foo", "Bar");
        return response;
    }

    @Post(value = "/writable", processes = MediaType.TEXT_PLAIN)
    @Header(name = "Foo", value = "Bar")
    @Status(HttpStatus.CREATED)
    Writable fullReq(@Body Readable readable) throws IOException {
        return out -> {
            try (BufferedReader reader = new BufferedReader(readable.asReader())) {
                out.append("Hello ").append(reader.readLine());
            }
        };

    }

    @Post(value = "/multipart", consumes = MediaType.MULTIPART_FORM_DATA, produces = MediaType.TEXT_PLAIN)
    String multipart(
            String foo,
            @Part("one") Person person,
            @Part("two") String text,
            @Part("three") byte[] bytes,
            @Part("four") jakarta.servlet.http.Part raw) throws IOException {
        return "Good: " + (foo.equals("bar") &&
                person.getName().equals("bar") &&
                text.equals("Whatever") &&
                new String(bytes).equals("My Doc") &&
                IOUtils.readText(new BufferedReader(new InputStreamReader(raw.getInputStream()))).equals("Another Doc"));
    }

}
