package io.micronaut.servlet.undertow

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Requires as SpockRequires
import spock.lang.Specification

/**
 * With virtual threads enabled, servlet invocations run on virtual threads rather than the XNIO worker pool.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'UndertowVirtualThreadsSpec')
@Property(name = 'micronaut.servlet.enable-virtual-threads', value = 'true')
@SpockRequires({ jvm.isJava21Compatible() })
class UndertowVirtualThreadsSpec extends Specification {

    @Inject
    @Client('/')
    HttpClient client

    void "a request runs on a virtual thread"() {
        when:
        String thread = client.toBlocking().retrieve(HttpRequest.GET('/threads/current'), String)

        then: "the flag used to be ignored on Undertow, leaving every request on an XNIO task thread"
        thread.startsWith('virtual:true')
        thread.contains('undertow-handler-')
    }

    void "many blocking requests run concurrently"() {
        given:
        int requests = 32
        long delayMillis = 200

        when:
        long start = System.nanoTime()
        def results = (1..requests).collect { n ->
            Thread.startVirtualThread {
                client.toBlocking().retrieve(HttpRequest.GET("/threads/sleep?ms=${delayMillis}"), String)
            }
        }
        results*.join()
        long elapsedMillis = (System.nanoTime() - start).intdiv(1_000_000)

        then: "handled serially this would take requests * delay; a pool of ~96 XNIO workers would also serialise past 96"
        elapsedMillis < (requests * delayMillis).intdiv(4)
    }

    @Requires(property = 'spec.name', value = 'UndertowVirtualThreadsSpec')
    @Controller('/threads')
    static class ThreadController {

        @Get(value = '/current', produces = MediaType.TEXT_PLAIN)
        String current() {
            Thread t = Thread.currentThread()
            "virtual:${t.isVirtual()} name:${t.name}"
        }

        @Get(value = '/sleep', produces = MediaType.TEXT_PLAIN)
        String sleep(long ms) {
            Thread.sleep(ms)
            'slept'
        }
    }
}
