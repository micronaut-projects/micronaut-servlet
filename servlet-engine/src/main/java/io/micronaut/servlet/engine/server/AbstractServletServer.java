package io.micronaut.servlet.engine.server;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.runtime.server.event.ServerStartupEvent;

/**
 * Abstract base class for servlet embedded servers.
 *
 * @author graemerocher
 * @since 1.0.0
 * @param <T> The server type
 */
public abstract class AbstractServletServer<T> implements EmbeddedServer {

    private final ApplicationContext applicationContext;
    private final ApplicationConfiguration applicationConfiguration;
    private final T server;

    /**
     * Default constructor.
     * @param applicationContext The application context
     * @param applicationConfiguration The application configuration
     */
    protected AbstractServletServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            T server) {
        this.applicationContext = applicationContext;
        this.applicationConfiguration = applicationConfiguration;
        this.server = server;
    }

    /**
     * @return The server object.
     */
    public T getServer() {
        return server;
    }

    @Override
    public final ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    @Override
    public final ApplicationConfiguration getApplicationConfiguration() {
        return applicationConfiguration;
    }

    @Override
    public final EmbeddedServer start() {
        try {
            if (!applicationContext.isRunning()) {
                applicationContext.start();
            }
            startServer();
            applicationContext.publishEvent(new ServerStartupEvent(this));
        } catch (Exception e) {
            throw new HttpServerException(
                    "Error starting HTTP server: " + e.getMessage(), e
            );
        }
        return this;
    }

    @Override
    public final EmbeddedServer stop() {
        try {
            stopServer();
            if (applicationContext.isRunning()) {
                applicationContext.stop();
            }
            applicationContext.publishEvent(new ServerShutdownEvent(this));
        } catch (Exception e) {
            throw new HttpServerException(
                    "Error stopping HTTP server: " + e.getMessage(), e
            );
        }
        return this;
    }

    /**
     * Start the server.
     *
     * @throws Exception when an error occurred starting the server
     */
    protected abstract void startServer() throws Exception;

    /**
     * Stop the server.
     * @throws Exception when an error occurred stopping the server
     */
    protected abstract void stopServer() throws Exception;
}
