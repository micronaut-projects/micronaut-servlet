/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.tomcat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.ApplicationEventPublisher;
import org.jspecify.annotations.Nullable;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.servlet.http.server.AbstractServletServer;
import jakarta.inject.Inject;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import jakarta.inject.Singleton;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
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
     * @param serverShutdownEventPublisher {@link ApplicationEventPublisher} for the {@link ServerShutdownEvent} event.
     * @param tomcat                   The tomcat instance
     */
    @Inject
    public TomcatServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            @Nullable ApplicationEventPublisher<ServerShutdownEvent> serverShutdownEventPublisher,
            Tomcat tomcat) {
        super(applicationContext, applicationConfiguration, serverShutdownEventPublisher, tomcat);
    }

    /**
     * Default constructor.
     *
     * @param applicationContext       The context
     * @param applicationConfiguration The configuration
     * @param tomcat                   The tomcat instance
     * @deprecated Use {@link TomcatServer(ApplicationContext, ApplicationConfiguration, ApplicationEventPublisher, Tomcat)} instead.
     */
    @Deprecated(forRemoval = true, since = "5.2.0")
    public TomcatServer(
        ApplicationContext applicationContext,
        ApplicationConfiguration applicationConfiguration,
        Tomcat tomcat) {
        this(applicationContext, applicationConfiguration, null, tomcat);
    }

    @Override
    protected void startServer() throws Exception {
        if (running.compareAndSet(false, true)) {
            Tomcat server = getServer();
            server.start();
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
        Connector[] connectors = getServer().getService().findConnectors();
        if (connectors.length == 1) {
            return getServer().getConnector().getLocalPort();
        } else {
            return Arrays.stream(connectors).filter(Connector::getSecure)
                    .findFirst()
                    .map(Connector::getLocalPort)
                    .orElseGet(() -> getServer().getConnector().getLocalPort());
        }
    }

    @Override
    public String getHost() {
        return getServer().getHost().getName();
    }

    @Override
    public String getScheme() {
        Connector[] connectors = getServer().getService().findConnectors();
        if (connectors.length == 1) {
            return getServer().getConnector().getScheme();
        } else {
            if (Arrays.stream(connectors).anyMatch(Connector::getSecure)) {
                return "https";
            } else {
                return "http";
            }
        }
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
