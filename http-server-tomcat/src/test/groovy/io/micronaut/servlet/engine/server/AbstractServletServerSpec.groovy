package io.micronaut.servlet.engine.server

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.ApplicationConfiguration
import spock.lang.Specification

class AbstractServletServerSpec extends Specification {

    void "AbstractServletServer::stop triggers ServerShutdownEvent only if the server is running"() {
        given:
        def applicationContext = Mock(ApplicationContext)
        def applicationConfiguration = Mock(ApplicationConfiguration)
        Object server = new Object()

        when:
        MockAbstractServletServer servletServer = new MockAbstractServletServer(applicationContext, applicationConfiguration, server)
        servletServer.stop()
        servletServer.stop()

        then:
        1 * applicationContext.publishEvent(_)

    }

    static class MockAbstractServletServer extends AbstractServletServer {

        boolean running = true

        protected MockAbstractServletServer(ApplicationContext applicationContext, ApplicationConfiguration applicationConfiguration, Object server) {
            super(applicationContext, applicationConfiguration, server)
        }

        @Override
        protected void startServer() throws Exception {

        }

        @Override
        protected void stopServer() throws Exception {
            running = false
        }

        @Override
        int getPort() {
            return 0
        }

        @Override
        String getHost() {
            return null
        }

        @Override
        String getScheme() {
            return null
        }

        @Override
        URL getURL() {
            return null
        }

        @Override
        URI getURI() {
            return null
        }

        @Override
        boolean isRunning() {
            running
        }
    }
}
