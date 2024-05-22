package io.micronaut.servlet.tomcat

import io.micronaut.scheduling.TaskExecutors
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Named
import org.apache.catalina.startup.Tomcat
import spock.lang.Requires
import spock.lang.Specification

import java.util.concurrent.ExecutorService

@MicronautTest
@Requires({ jvm.java21 })
class TomcatVirtualThreadSpec  extends Specification {
    @Inject Tomcat server
    @Inject @Named(TaskExecutors.BLOCKING) ExecutorService taskExecutor

    void "test virtual thread enabled on JDK 21+"() {
        expect:
        server.connector.protocolHandler.executor.is(taskExecutor)
    }
}
