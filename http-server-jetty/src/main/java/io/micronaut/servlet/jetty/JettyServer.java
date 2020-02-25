package io.micronaut.servlet.jetty;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;

import javax.inject.Singleton;
import java.net.InetSocketAddress;
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
public class JettyServer implements EmbeddedServer {

    private final Server server;
    private final ApplicationContext applicationContext;
    private final ApplicationConfiguration applicationConfiguration;

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
        this.applicationContext = applicationContext;
        this.applicationConfiguration = applicationConfiguration;
        this.server = server;
    }

    /**
     * @return The underlying Jetty server
     */
    public Server getServer() {
        return server;
    }

    @Override
    public EmbeddedServer start() {
        try {
            if (!applicationContext.isRunning()) {
                applicationContext.start();
            }
            server.start();
        } catch (Exception e) {
            throw new HttpServerException(
                    "Error starting Jetty server: " + e.getMessage(), e
            );
        }
        return this;
    }

    @Override
    public EmbeddedServer stop() {
        try {
            server.stop();
            if (applicationContext.isRunning()) {
                applicationContext.stop();
            }
        } catch (Exception e) {
            throw new HttpServerException(
                    "Error stopping Jetty server: " + e.getMessage(), e
            );
        }
        return this;
    }

    @Override
    public int getPort() {
        return server.getURI().getPort();
    }

    @Override
    public String getHost() {
        return server.getURI().getHost();
    }

    @Override
    public String getScheme() {
        return server.getURI().getScheme();
    }

    @Override
    public URL getURL() {
        try {
            return server.getURI().toURL();
        } catch (MalformedURLException e) {
            throw new HttpServerException(e.getMessage(), e);
        }
    }

    @Override
    public URI getURI() {
        return server.getURI();
    }

    @Override
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    @Override
    public ApplicationConfiguration getApplicationConfiguration() {
        return applicationConfiguration;
    }

    @Override
    public boolean isRunning() {
        return server.isRunning();
    }
}
