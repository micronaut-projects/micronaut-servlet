package io.micronaut.servlet.tomcat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.http.server.exceptions.ServerStartupException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

import javax.inject.Singleton;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

/**
 * Implementation of {@link EmbeddedServer} for Tomcat.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
public class TomcatServer implements EmbeddedServer {

    private final Tomcat tomcat;
    private final ApplicationContext applicationContext;
    private final ApplicationConfiguration applicationConfiguration;

    /**
     * Default constructor.
     *
     * @param applicationContext       The context
     * @param applicationConfiguration The configuration
     * @param tomcat                   The tomcat instance
     */
    public TomcatServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            Tomcat tomcat) {
        this.applicationContext = applicationContext;
        this.applicationConfiguration = applicationConfiguration;
        this.tomcat = tomcat;
    }


    @Override
    public EmbeddedServer start() {
        try {
            if (!applicationContext.isRunning()) {
                applicationContext.start();
            }
            tomcat.start();
        } catch (LifecycleException e) {
            throw new ServerStartupException("Error starting Tomcat server: " + e.getMessage(), e);
        }
        return this;
    }

    @Override
    public EmbeddedServer stop() {
        try {
            if (applicationContext.isRunning()) {
                applicationContext.stop();
            }
            tomcat.stop();
        } catch (LifecycleException e) {
            throw new HttpServerException("Error shutting down Tomcat server: " + e.getMessage(), e);
        }
        return this;
    }

    @Override
    public int getPort() {
        return tomcat.getConnector().getPort();
    }

    @Override
    public String getHost() {
        return tomcat.getHost().getName();
    }

    @Override
    public String getScheme() {
        return tomcat.getConnector().getScheme();
    }

    @Override
    public URL getURL() {
        try {
            return getURI().toURL();
        } catch (MalformedURLException e) {
            throw new InternalServerException(e.getMessage(), e);
        }
    }

    @Override
    public URI getURI() {
        return URI.create(getScheme() + "://" + getHost() + ":" + getPort());
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
        return applicationContext.isRunning();
    }
}
