package io.micronaut.http.poja

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpRequestWrapper
import io.micronaut.http.MediaType
import io.micronaut.http.ServerHttpRequest
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * A reactive body consumer that keeps reading on the blocking executor after the response has been sent must not
 * read the session stream at the same time as the runtime discards the rest of the body.
 */
@MicronautTest
@Property(name = "spec.name", value = "ReactiveBodyFramingSpec")
@Property(name = "micronaut.executors.blocking.type", value = "fixed")
@Property(name = "micronaut.executors.blocking.n-threads", value = "2")
@Property(name = "poja.apache.discard-timeout", value = "500ms")
class ReactiveBodyFramingSpec extends Specification {

    @Inject
    EmbeddedServer server

    void "a body still read by a reactive consumer after the response is not parsed as the next request"() {
        given:
        String smuggled = "GET /reactive/admin HTTP/1.1\r\nHost: h\r\n\r\n"
        String body = ("a" * 65536) + smuggled

        when:
        String response = exchange(
            "POST /reactive/slow HTTP/1.1\r\nHost: h\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.length()}\r\n\r\n" + body +
                "GET /reactive/ok HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")

        then:
        (response =~ /HTTP\/1\.1 \d{3}/).collect { it as String } == ["HTTP/1.1 200", "HTTP/1.1 200"]
        response.contains("ok")
        !response.contains("admin")
    }

    void "the discard timeout applies while a reactive consumer is blocked reading a stalled body"() {
        given: "a body the client stops sending part way, on a connection it keeps open"
        Socket socket = new Socket(server.host, server.port)
        socket.setSoTimeout(10_000)
        socket.getOutputStream().write(("POST /reactive/slow HTTP/1.1\r\nHost: h\r\nContent-Type: application/octet-stream\r\nContent-Length: 1000\r\n\r\n" + ("a" * 10))
            .getBytes(StandardCharsets.ISO_8859_1))
        socket.getOutputStream().flush()

        when:
        long start = System.nanoTime()
        String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1)
        long elapsedMillis = (System.nanoTime() - start).intdiv(1_000_000)

        then: "the response is sent and the connection closed once the timeout has passed"
        response.startsWith("HTTP/1.1 200")
        elapsedMillis < 5000

        cleanup:
        socket.close()
    }

    private String exchange(String request) {
        try (Socket socket = new Socket(server.host, server.port)) {
            socket.setSoTimeout(10_000)
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1))
            socket.getOutputStream().flush()
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1)
        }
    }

    @Requires(property = "spec.name", value = "ReactiveBodyFramingSpec")
    @Controller(value = "/reactive", produces = MediaType.TEXT_PLAIN)
    static class ReactiveController {

        @Post(value = "/slow", consumes = MediaType.APPLICATION_OCTET_STREAM)
        String slow(HttpRequest<?> request) {
            // fire and forget: the body is still being read after the response has been sent
            HttpRequest<?> unwrapped = request
            while (unwrapped instanceof HttpRequestWrapper) {
                unwrapped = ((HttpRequestWrapper<?>) unwrapped).getDelegate()
            }
            Publisher<byte[]> body = ((ServerHttpRequest<?>) unwrapped).byteBody().toByteArrayPublisher()
            body.subscribe(new Subscriber<byte[]>() {
                Subscription subscription

                @Override
                void onSubscribe(Subscription s) {
                    subscription = s
                    s.request(1)
                }

                @Override
                void onNext(byte[] bytes) {
                    Thread.sleep(1)
                    subscription.request(1)
                }

                @Override
                void onError(Throwable t) {
                    // the runtime takes over the body once the response has been sent
                }

                @Override
                void onComplete() {
                    // read to the end
                }
            })
            return "accepted"
        }

        @Get("/ok")
        String ok() {
            return "ok"
        }

        @Get("/admin")
        String admin() {
            return "admin"
        }
    }
}
