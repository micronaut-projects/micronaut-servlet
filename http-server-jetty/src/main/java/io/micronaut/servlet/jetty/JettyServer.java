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
package io.micronaut.servlet.jetty;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.servlet.engine.server.AbstractServletServer;
import org.eclipse.jetty.server.Server;

import jakarta.inject.Singleton;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

/**
 * An implementation of the {@link io.micronaut.runtime.server.EmbeddedServer} interface for Jetty.
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
        Server server = getServer();
        return server.getURI().getPort();
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
