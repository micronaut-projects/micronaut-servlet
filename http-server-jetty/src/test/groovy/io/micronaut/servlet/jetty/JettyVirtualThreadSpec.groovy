package io.micronaut.servlet.jetty

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.thread.QueuedThreadPool
import spock.lang.Requires
import spock.lang.Specification

@MicronautTest
@Requires({ jvm.java21 })
class JettyVirtualThreadSpec extends Specification {
    @Inject Server server

    void "test virtual thread enabled on JDK 21+"() {
        expect:
        server.threadPool instanceof QueuedThreadPool
        server.threadPool.virtualThreadsExecutor != null

    }
}
