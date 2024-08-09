package io.micronaut.http.poja


import io.micronaut.core.annotation.NonNull
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.test.extensions.spock.annotation.MicronautTest

@MicronautTest
class SimpleServerSpec extends BaseServerlessApplicationSpec {

    void "test GET method"() {
        when:
        app.write("""\
        GET /test HTTP/1.1
        Host: h

        """.stripIndent())

        then:
        app.read() == """\
        HTTP/1.1 200 Ok
        Content-Type: text/plain
        Content-Length: 32

        Hello, Micronaut Without Netty!
        """.stripIndent()
    }

    void "test invalid GET method"() {
        when:
        app.write("""\
        GET /invalid-test HTTP/1.1
        Host: h

        """.stripIndent())

        then:
        app.read() == """\
        HTTP/1.1 404 Not Found
        Content-Type: application/json
        Content-Length: 148

        {"_links":{"self":[{"href":"http://h/invalid-test","templated":false}]},"_embedded":{"errors":[{"message":"Page Not Found"}]},"message":"Not Found"}""".stripIndent()
    }

    void "test DELETE method"() {
        when:
        app.write("""\
        DELETE /test HTTP/1.1
        Host: h

        """.stripIndent())

        then:
        app.read() == """\
        HTTP/1.1 200 Ok
        Content-Length: 0

        """.stripIndent()
    }

    void "test POST method"() {
        when:
        app.write("""\
        POST /test/Dream HTTP/1.1
        Host: h

        """.stripIndent())

        then:
        app.read() == """\
        HTTP/1.1 201 Created
        Content-Type: text/plain
        Content-Length: 13

        Hello, Dream
        """.stripIndent()
    }

    void "test PUT method"() {
        when:
        app.write("""\
        PUT /test/Dream1 HTTP/1.1
        Host: h

        """.stripIndent())

        then:
        app.read() == """\
        HTTP/1.1 200 Ok
        Content-Type: text/plain
        Content-Length: 15

        Hello, Dream1!
        """.stripIndent()
    }

    /**
     * A controller for testing.
     */
    @Controller(value = "/test", produces = MediaType.TEXT_PLAIN, consumes = MediaType.ALL)
    static class TestController {

        @Get
        String index() {
            return "Hello, Micronaut Without Netty!\n"
        }

        @Delete
        void delete() {
            System.err.println("Delete called")
        }

        @Post("/{name}")
        @Status(HttpStatus.CREATED)
        String create(@NonNull String name) {
            return "Hello, " + name + "\n"
        }

        @Put("/{name}")
        @Status(HttpStatus.OK)
        String update(@NonNull String name) {
            return "Hello, " + name + "!\n"
        }

    }

}
