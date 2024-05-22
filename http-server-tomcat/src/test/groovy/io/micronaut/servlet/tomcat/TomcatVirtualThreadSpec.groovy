package io.micronaut.servlet.tomcat


import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.util.threads.VirtualThreadExecutor
import spock.lang.Requires
import spock.lang.Specification

@MicronautTest
@Requires({ jvm.java21 })
class TomcatVirtualThreadSpec  extends Specification {
    @Inject Tomcat server

    void "test virtual thread enabled on JDK 21+"() {
        expect:
        server.connector.protocolHandler.executor instanceof VirtualThreadExecutor
    }
}
