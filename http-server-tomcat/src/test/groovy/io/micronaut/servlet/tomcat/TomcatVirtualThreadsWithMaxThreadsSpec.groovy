package io.micronaut.servlet.tomcat

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
 * Setting a thread limit must not turn virtual threads off.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'TomcatVirtualThreadsWithMaxThreadsSpec')
@Property(name = 'micronaut.servlet.enable-virtual-threads', value = 'true')
@Property(name = 'micronaut.servlet.max-threads', value = '50')
@SpockRequires({ jvm.isJava21Compatible() })
class TomcatVirtualThreadsWithMaxThreadsSpec extends Specification {

    @Inject
    @Client('/')
    HttpClient client

    void "a request still runs on a virtual thread when max-threads is configured"() {
        when:
        String thread = client.toBlocking().retrieve(HttpRequest.GET('/vt-max/current'), String)

        then: "a platform pool sized by max-threads used to replace the virtual thread executor"
        thread.startsWith('virtual:true')
    }

    @Requires(property = 'spec.name', value = 'TomcatVirtualThreadsWithMaxThreadsSpec')
    @Controller('/vt-max')
    static class ThreadController {

        @Get(value = '/current', produces = MediaType.TEXT_PLAIN)
        String current() {
            Thread t = Thread.currentThread()
            "virtual:${t.isVirtual()} name:${t.name}"
        }
    }
}
