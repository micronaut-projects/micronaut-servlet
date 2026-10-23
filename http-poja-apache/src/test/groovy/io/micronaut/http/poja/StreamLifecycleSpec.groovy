package io.micronaut.http.poja

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.context.env.Environment
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.poja.apache.ApacheServerlessApplication
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class StreamLifecycleSpec extends Specification {

    ApplicationContext context
    ApacheServerlessApplication application

    void setup() {
        context = ApplicationContext.run(['spec.name': 'StreamLifecycleSpec'], Environment.FUNCTION)
        application = context.getBean(ApacheServerlessApplication)
    }

    void cleanup() {
        Thread.interrupted()
        context.close()
    }

    void "standalone start still stops the application on end of input without an error response"() {
        given:
        def output = new ByteArrayOutputStream()

        when:
        application.start(new ByteArrayInputStream(new byte[0]), output)

        then:
        !context.running
        Thread.currentThread().isInterrupted()
        output.size() == 0
    }

    void "closing a handled connection keeps the shared application running"() {
        given:
        def output = new ByteArrayOutputStream()

        when:
        application.handleConnection(new ByteArrayInputStream(new byte[0]), output)

        then:
        context.running
        !Thread.currentThread().isInterrupted()
        output.size() == 0
    }

    void "parser state is discarded between connections handled on the same thread"() {
        given:
        String request = "GET /stream-lifecycle HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"
        def first = new ByteArrayOutputStream()
        def second = new ByteArrayOutputStream()

        when:
        application.start(new ByteArrayInputStream((request + "GET /invalid HTTP/1.1\r\nHost: h\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8)), first)
        application.start(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), second)

        then:
        first.toString(StandardCharsets.UTF_8).startsWith('HTTP/1.1 200')
        second.toString(StandardCharsets.UTF_8).startsWith('HTTP/1.1 200')
        second.toString(StandardCharsets.UTF_8).endsWith('stream-ok')
        context.running
    }

    @Controller('/stream-lifecycle')
    @Requires(property = 'spec.name', value = 'StreamLifecycleSpec')
    static class StreamController {
        @Get(produces = MediaType.TEXT_PLAIN)
        String index() {
            'stream-ok'
        }
    }
}
