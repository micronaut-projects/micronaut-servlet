package io.micronaut.servlet.undertow;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import io.undertow.Undertow;

import javax.inject.Singleton;
import java.net.*;

@Singleton
public class UndertowServer implements EmbeddedServer {

    private final Undertow undertow;
    private final ApplicationContext applicationContext;
    private final ApplicationConfiguration applicationConfiguration;

    public UndertowServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            Undertow undertow) {
        this.applicationConfiguration = applicationConfiguration;
        this.applicationContext = applicationContext;
        this.undertow = undertow;
    }

    @Override
    public EmbeddedServer start() {
        if (!applicationContext.isRunning()) {
            applicationContext.start();
        }
        undertow.start();
        return this;
    }

    @Override
    public EmbeddedServer stop() {
        undertow.stop();
        if (applicationContext.isRunning()) {
            applicationContext.stop();
        }
        return this;
    }

    @Override
    public int getPort() {
        final SocketAddress address = undertow.getListenerInfo().get(0).getAddress();
        return ((InetSocketAddress) address).getPort();
    }

    @Override
    public String getHost() {
        final SocketAddress address = undertow.getListenerInfo().get(0).getAddress();
        return ((InetSocketAddress) address).getHostName();
    }

    @Override
    public String getScheme() {
        return undertow.getListenerInfo().get(0).getProtcol();
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
