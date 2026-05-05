package io.micronaut.http.poja

import io.micronaut.context.ApplicationContext
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.poja.apache.ApacheServerlessApplication
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class ApacheBlockingExecutorSpec extends Specification {

    void "blocking executor runs on calling thread in poja"() {
        given:
        Thread thread = Thread.currentThread()
        String previousName = thread.name
        String expectedThreadName = "poja-single-thread-test"
        thread.name = expectedThreadName

        when:
        String body
        try (ApplicationContext context = ApplicationContext.run()) {
            ApacheServerlessApplication application = context.getBean(ApacheServerlessApplication)
            ByteArrayOutputStream output = new ByteArrayOutputStream()
            application.start(new ByteArrayInputStream(request("/thread-name")), output)
            body = responseBody(output)
        }

        then:
        body == expectedThreadName

        cleanup:
        thread.name = previousName
    }

    void "explicit blocking executor configuration overrides poja default"() {
        given:
        Thread thread = Thread.currentThread()
        String previousName = thread.name
        String expectedThreadName = "poja-configured-blocking-test"
        thread.name = expectedThreadName

        when:
        String body
        try (ApplicationContext context = ApplicationContext.builder(
            "micronaut.executors.blocking.type": "fixed",
            "micronaut.executors.blocking.n-threads": "1"
        ).start()) {
            ApacheServerlessApplication application = context.getBean(ApacheServerlessApplication)
            ByteArrayOutputStream output = new ByteArrayOutputStream()
            application.start(new ByteArrayInputStream(request("/thread-name")), output)
            body = responseBody(output)
        }

        then:
        body != expectedThreadName

        cleanup:
        thread.name = previousName
    }

    private static byte[] request(String path) {
        ("GET ${path} HTTP/1.1\r\n" +
            "Connection: close\r\n" +
            "Host: h\r\n" +
            "\r\n").getBytes(StandardCharsets.UTF_8)
    }

    private static String responseBody(ByteArrayOutputStream output) {
        String response = output.toString(StandardCharsets.UTF_8)
        response.substring(response.indexOf("\r\n\r\n") + 4)
    }

    @Controller
    static final class ThreadNameController {

        @Get("/thread-name")
        @ExecuteOn(TaskExecutors.BLOCKING)
        String threadName() {
            Thread.currentThread().name
        }
    }
}
