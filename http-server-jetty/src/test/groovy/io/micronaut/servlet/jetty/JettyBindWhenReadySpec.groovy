package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.core.io.socket.SocketUtils
import io.micronaut.runtime.server.EmbeddedServer
import jakarta.inject.Singleton
import jakarta.servlet.ServletContainerInitializer
import jakarta.servlet.ServletContext
import spock.lang.Specification

class JettyBindWhenReadySpec extends Specification {

    static final String SPEC_NAME = "JettyBindWhenReadySpec"

    void "the port accepts connections only after the servlet context has started"() {
        given:
        int port = SocketUtils.findAvailableTcpPort()

        when:
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, [
            'spec.name'            : SPEC_NAME,
            'micronaut.server.port': port
        ])
        PortProbe probe = server.applicationContext.getBean(PortProbe)

        then:
        probe.port == port
        probe.invoked
        !probe.acceptedDuringStartup
        accepts(port)

        cleanup:
        server?.close()
    }

    static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.loopbackAddress, port), 200)
            return true
        } catch (IOException ignored) {
            return false
        }
    }

    @Singleton
    @Requires(property = "spec.name", value = SPEC_NAME)
    static class PortProbe implements ServletContainerInitializer {

        int port
        boolean invoked
        boolean acceptedDuringStartup

        PortProbe(ApplicationContext context) {
            port = context.getProperty("micronaut.server.port", Integer).orElse(-1)
        }

        @Override
        void onStartup(Set<Class<?>> c, ServletContext ctx) {
            invoked = true
            acceptedDuringStartup = JettyBindWhenReadySpec.accepts(port)
        }
    }
}
