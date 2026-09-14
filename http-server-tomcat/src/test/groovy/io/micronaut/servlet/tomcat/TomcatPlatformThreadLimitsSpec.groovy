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
import org.apache.catalina.core.StandardThreadExecutor
import org.apache.catalina.startup.Tomcat
import spock.lang.Specification

/**
 * With virtual threads off, the thread limits size a platform pool that the connector runs requests on.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'TomcatPlatformThreadLimitsSpec')
@Property(name = 'micronaut.servlet.enable-virtual-threads', value = 'false')
@Property(name = 'micronaut.servlet.max-threads', value = '8')
@Property(name = 'micronaut.servlet.min-threads', value = '2')
class TomcatPlatformThreadLimitsSpec extends Specification {

    @Inject
    @Client('/')
    HttpClient client

    @Inject
    Tomcat tomcat

    void "the connector runs requests on a pool sized by the configured limits"() {
        when:
        String thread = client.toBlocking().retrieve(HttpRequest.GET('/platform-pool/current'), String)

        then: "the pool's threads carry Tomcat's executor prefix"
        thread.startsWith('virtual:false name:tomcat-exec-')

        and:
        StandardThreadExecutor executor = tomcat.service.findExecutors().find { it instanceof StandardThreadExecutor }
        executor.name == 'tomcatThreadPool'
        executor.maxThreads == 8
        executor.minSpareThreads == 2
        tomcat.connector.protocolHandler.executor.is(executor)
    }

    @Requires(property = 'spec.name', value = 'TomcatPlatformThreadLimitsSpec')
    @Controller('/platform-pool')
    static class ThreadController {

        @Get(value = '/current', produces = MediaType.TEXT_PLAIN)
        String current() {
            Thread t = Thread.currentThread()
            "virtual:${t.isVirtual()} name:${t.name}"
        }
    }
}
