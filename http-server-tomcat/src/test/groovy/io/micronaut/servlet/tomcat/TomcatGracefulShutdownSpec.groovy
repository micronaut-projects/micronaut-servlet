package io.micronaut.servlet.tomcat

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.runtime.graceful.GracefulShutdownCapable
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.servlet.http.ServletHttpHandler
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * With graceful shutdown enabled, stopping the application lets requests that are already being handled finish
 * instead of cutting them off.
 */
class TomcatGracefulShutdownSpec extends Specification {

    void "the server is graceful shutdown capable and reports in-flight requests"() {
        given:
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, ['spec.name': 'TomcatGracefulShutdownSpec'])

        expect:
        server instanceof GracefulShutdownCapable
        ((GracefulShutdownCapable) server).reportActiveTasks().asLong == 0
        server.applicationContext.getBean(ServletHttpHandler).activeRequests == 0

        cleanup:
        server.close()
    }

    void "stopping the application waits for an in-flight request to complete"() {
        given:
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, [
            'spec.name'                                   : 'TomcatGracefulShutdownSpec',
            'micronaut.lifecycle.graceful-shutdown.enabled': true
        ])
        SlowController controller = server.applicationContext.getBean(SlowController)
        HttpClient client = HttpClient.newHttpClient()
        HttpRequest request = HttpRequest.newBuilder(server.URI.resolve('/graceful/slow')).build()

        when: "a request is in progress while the application is stopped"
        CompletableFuture<HttpResponse<String>> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        assert controller.started.await(10, TimeUnit.SECONDS)
        GracefulShutdownCapable capable = (GracefulShutdownCapable) server
        long activeDuringRequest = capable.reportActiveTasks().asLong
        long stopStarted = System.nanoTime()
        server.applicationContext.stop()
        long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStarted)

        then: "the request was counted and the stop waited for it"
        activeDuringRequest == 1
        response.get(10, TimeUnit.SECONDS).statusCode() == 200
        response.get().body() == 'finished'
        controller.released
        stopMillis >= SlowController.WORK.toMillis() / 2

        cleanup:
        server.close()
    }

    @Requires(property = 'spec.name', value = 'TomcatGracefulShutdownSpec')
    @Controller('/graceful')
    static class SlowController {
        static final Duration WORK = Duration.ofMillis(1500)
        final CountDownLatch started = new CountDownLatch(1)
        volatile boolean released

        @Get('/slow')
        String slow() {
            started.countDown()
            Thread.sleep(WORK.toMillis())
            released = true
            'finished'
        }
    }
}
