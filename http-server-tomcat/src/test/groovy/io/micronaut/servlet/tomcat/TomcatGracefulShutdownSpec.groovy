package io.micronaut.servlet.tomcat

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.runtime.graceful.GracefulShutdownCapable
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.servlet.http.ServletHttpHandler
import spock.lang.Specification

import java.time.Duration
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
        // held until cleanup: a JDK HttpClient that becomes unreachable while a request is in flight has its
        // channels closed by the collector, and the request fails with a ClosedChannelException on retry
        // a raw socket rather than the JDK HttpClient: the client retries a GET on a new connection when it decides
        // the first one failed, which turns an in-flight response into a ConnectException against a stopped server
        Socket socket = new Socket('localhost', server.port)
        socket.outputStream.write("GET /graceful/slow HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".bytes)
        socket.outputStream.flush()
        StringBuilder raw = new StringBuilder()
        Thread reader = Thread.startVirtualThread {
            try {
                socket.inputStream.withReader('ISO-8859-1') { r -> int ch; while ((ch = r.read()) != -1) { raw.append((char) ch) } }
            } catch (IOException e) {
                raw.append('<<').append(e).append('>>')
            }
        }

        when: "a request is in progress while the application is stopped"
        assert controller.started.await(10, TimeUnit.SECONDS)
        GracefulShutdownCapable capable = (GracefulShutdownCapable) server
        long activeDuringRequest = capable.reportActiveTasks().asLong
        long stopStarted = System.nanoTime()
        server.applicationContext.stop()
        long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStarted)
        reader.join(10_000)
        String response = raw.toString()

        then: "the request was counted and the stop waited for it"
        activeDuringRequest == 1
        response.startsWith('HTTP/1.1 200')
        response.endsWith('finished')
        controller.released
        stopMillis >= SlowController.WORK.toMillis() / 2

        cleanup:
        socket.close()
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
