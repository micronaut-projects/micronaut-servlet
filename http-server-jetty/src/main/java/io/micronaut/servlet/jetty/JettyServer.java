package io.micronaut.servlet.jetty;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.servlet.engine.server.AbstractServletServer;
import org.eclipse.jetty.server.Server;

import javax.inject.Singleton;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

/**
 * An implementation of the {@link EmbeddedServer} interface for Jetty.
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
public class JettyServer extends AbstractServletServer<Server> {


    /**
     * Default constructor.
     *
     * @param applicationContext       The application context
     * @param applicationConfiguration The application configuration
     * @param server                   The jetty server
     */
    public JettyServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            Server server) {
        super(applicationContext, applicationConfiguration, server);
    }

    @Override
    protected void startServer() throws Exception {
        getServer().start();
    }

    @Override
    protected void stopServer() throws Exception {
        getServer().stop();
    }

    @Override
    public int getPort() {
        return getServer().getURI().getPort();
    }

    @Override
    public String getHost() {
        return getServer().getURI().getHost();
    }

    @Override
    public String getScheme() {
        return getServer().getURI().getScheme();
    }

    @Override
    public URL getURL() {
        try {
            return getServer().getURI().toURL();
        } catch (MalformedURLException e) {
            throw new HttpServerException(e.getMessage(), e);
        }
    }

    @Override
    public URI getURI() {
        return getServer().getURI();
    }

    @Override
    public boolean isRunning() {
        return getServer().isRunning();
    }
}
