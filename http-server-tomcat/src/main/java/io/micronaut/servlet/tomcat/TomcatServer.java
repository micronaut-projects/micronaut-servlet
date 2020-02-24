package io.micronaut.servlet.tomcat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.http.server.exceptions.ServerStartupException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import javax.inject.Singleton;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

@Singleton
public class TomcatServer implements EmbeddedServer {

    private final Tomcat tomcat;
    private final ApplicationContext applicationContext;
    private final ApplicationConfiguration applicationConfiguration;
    private final Integer port;
    private final String host;

    public TomcatServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            HttpServerConfiguration configuration) {
        this.applicationContext = applicationContext;
        this.applicationConfiguration = applicationConfiguration;
        this.tomcat = new Tomcat();
        this.port = configuration.getPort().map( p ->
            p == -1 ? SocketUtils.findAvailableTcpPort() : p
        ).orElse(8080);
        this.host = configuration.getHost().orElse("localhost");
        final String contextPath = configuration.getContextPath();
        this.tomcat.getHost().setAutoDeploy(false);
        this.tomcat.setConnector(newConnector());
        final Context context = tomcat.addContext(contextPath != null ? contextPath : "", "/");
        Tomcat.addServlet(
                context,
                "micronaut",
                DefaultMicronautServlet.class.getName()
        ).addMapping("/*");

    }

    /**
     * @return Create the protocol.
     */
    protected Connector newConnector() {
        final Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        return connector;
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
        return host;
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
