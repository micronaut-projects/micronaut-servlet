/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.servlet.engine.server;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link EmbeddedServer} bridge for servlet-container deployments.
 */
@Internal
@Singleton
@Requires(beans = ServletContext.class)
@Requires(missingBeans = EmbeddedServer.class)
public final class ServletContextEmbeddedServer implements EmbeddedServer {
    private final ApplicationContext applicationContext;
    private final ApplicationConfiguration applicationConfiguration;
    private final ServletContext servletContext;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public ServletContextEmbeddedServer(ApplicationContext applicationContext,
                                        ApplicationConfiguration applicationConfiguration,
                                        ServletContext servletContext) {
        this.applicationContext = applicationContext;
        this.applicationConfiguration = applicationConfiguration;
        this.servletContext = servletContext;
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
    public EmbeddedServer start() {
        running.set(true);
        return this;
    }

    @Override
    public EmbeddedServer stop() {
        running.set(false);
        return this;
    }

    @Override
    public int getPort() {
        return applicationContext.getEnvironment()
            .getProperty("micronaut.server.port", Integer.class)
            .orElse(-1);
    }

    @Override
    public String getHost() {
        return applicationContext.getEnvironment()
            .getProperty("micronaut.server.host", String.class)
            .orElse("localhost");
    }

    @Override
    public String getScheme() {
        boolean sslEnabled = applicationContext.getEnvironment()
            .getProperty("micronaut.ssl.enabled", Boolean.class)
            .orElse(false);
        return sslEnabled ? "https" : "http";
    }

    @Override
    public URL getURL() {
        try {
            return getURI().toURL();
        } catch (Exception e) {
            throw new IllegalStateException("Invalid servlet container URL", e);
        }
    }

    @Override
    public URI getURI() {
        String contextPath = servletContext.getContextPath();
        String path = contextPath == null || contextPath.isEmpty() ? null : contextPath;
        int port = getPort();
        try {
            return new URI(getScheme(), null, getHost(), port > 0 ? port : -1, path, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid servlet container URI", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get() && applicationContext.isRunning();
    }
}
