package io.micronaut.servlet.tomcat

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.server.EmbeddedServer
import org.apache.catalina.startup.Tomcat
import org.apache.coyote.AbstractProtocol
import spock.lang.Specification

/**
 * micronaut.server.host is the address the connector binds, as on the JDK and Netty servers: without it Tomcat
 * listens on every interface.
 */
class TomcatServerHostSpec extends Specification {

    void "the connector binds the configured host"() {
        given:
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, ['micronaut.server.host': '127.0.0.1'])

        expect:
        address(server) == InetAddress.getByName('127.0.0.1')
        new Socket('127.0.0.1', server.port).withCloseable { it.connected }

        cleanup:
        server.close()
    }

    void "without a configured host the connector listens on every interface"() {
        given:
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer)

        expect:
        address(server) == null

        cleanup:
        server.close()
    }

    private static InetAddress address(EmbeddedServer server) {
        Tomcat tomcat = server.applicationContext.getBean(Tomcat)
        return ((AbstractProtocol) tomcat.connector.protocolHandler).address
    }
}
