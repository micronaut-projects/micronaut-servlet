package io.micronaut.servlet.jetty;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;

import javax.inject.Singleton;
import java.net.InetSocketAddress;

/**
 * Factory for the Jetty server.
 *
 * @author graemerocher
 * @since 1.0
 */
@Factory
public class JettyFactory extends ServletServerFactory {

    /**
     * Default constructor.
     * @param resourceResolver The resource resolver
     * @param serverConfiguration The server config
     * @param sslConfiguration The SSL config
     */
    public JettyFactory(
            ResourceResolver resourceResolver,
            HttpServerConfiguration serverConfiguration,
            SslConfiguration sslConfiguration) {
        super(resourceResolver, serverConfiguration, sslConfiguration);
    }

    /**
     * Builds the Jetty server bean.
     * @param handler The servlet handler
     * @return The Jetty server bean
     */
    @Singleton
    @Primary
    protected Server jettyServer(ServletHandler handler) {
        final String host = getConfiguredHost();
        final Integer port = getConfiguredPort();
        Server server;
        if (host != null) {

            server = new Server(
                    new InetSocketAddress(
                            host,
                            port
                    )
            );
        } else {
            server = new Server(port);
        }

        server.setHandler(handler);
        handler.addServletWithMapping(
                DefaultMicronautServlet.class,
                "/*"
        );

        return server;
    }

    /**
     * @return The ServletHandler bean.
     */
    @Singleton
    @Primary
    protected ServletHandler servletHandler() {
        return new ServletHandler();
    }
}
