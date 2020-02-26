package io.micronaut.servlet.tomcat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.servlet.engine.server.AbstractServletServer;
import org.apache.catalina.startup.Tomcat;

import javax.inject.Singleton;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of {@link io.micronaut.runtime.server.EmbeddedServer} for Tomcat.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
public class TomcatServer extends AbstractServletServer<Tomcat> {

    private final AtomicBoolean running = new AtomicBoolean(false);

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
        super(applicationContext, applicationConfiguration, tomcat);
    }

    @Override
    protected void startServer() throws Exception {
        if (running.compareAndSet(false, true)) {
            getServer().start();
        }
    }

    @Override
    protected void stopServer() throws Exception {
        if (running.compareAndSet(true, false)) {
            Tomcat tomcat = getServer();
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @Override
    public int getPort() {
        return getServer().getConnector().getPort();
    }

    @Override
    public String getHost() {
        return getServer().getHost().getName();
    }

    @Override
    public String getScheme() {
        return getServer().getConnector().getScheme();
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
    public boolean isRunning() {
        return running.get();
    }
}
