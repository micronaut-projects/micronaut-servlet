package io.micronaut.servlet.undertow;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.servlet.engine.server.AbstractServletServer;
import io.undertow.Undertow;

import javax.inject.Singleton;
import java.net.*;

/**
 * Implementation of {@link AbstractServletServer} for Undertow.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
public class UndertowServer extends AbstractServletServer<Undertow> {

    /**
     * Default constructor.
     * @param applicationContext The app context
     * @param applicationConfiguration The app config
     * @param undertow The undertow instance
     */
    public UndertowServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            Undertow undertow) {
        super(applicationContext, applicationConfiguration, undertow);
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
        final SocketAddress address = getServer().getListenerInfo().get(0).getAddress();
        return ((InetSocketAddress) address).getPort();
    }

    @Override
    public String getHost() {
        final SocketAddress address = getServer().getListenerInfo().get(0).getAddress();
        return ((InetSocketAddress) address).getHostName();
    }

    @Override
    public String getScheme() {
        return getServer().getListenerInfo().get(0).getProtcol();
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
        return getApplicationContext().isRunning();
    }
}
