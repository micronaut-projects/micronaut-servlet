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
package io.micronaut.servlet.engine.server;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.ApplicationEvent;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.discovery.ServiceInstance;
import io.micronaut.discovery.event.ServiceReadyEvent;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.runtime.server.event.ServerStartupEvent;

/**
 * Abstract base class for servlet embedded servers.
 *
 * @param <T> The server type
 * @author graemerocher
 * @since 1.0.0
 */
public abstract class AbstractServletServer<T> implements EmbeddedServer {

    private final ApplicationContext applicationContext;
    private final ApplicationConfiguration applicationConfiguration;
    private final ApplicationEventPublisher<ApplicationEvent> applicationEventPublisher;
    private final T server;

    /**
     * Default constructor.
     *
     * @param applicationContext       The application context
     * @param applicationConfiguration The application configuration
     * @param server                   The server object
     */
    protected AbstractServletServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            T server) {
        this.applicationContext = applicationContext;
        this.applicationConfiguration = applicationConfiguration;
        this.server = server;
        this.applicationEventPublisher = applicationContext.getEventPublisher(ApplicationEvent.class);
    }

    /**
     * @return The server object.
     */
    public final T getServer() {
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
            applicationEventPublisher.publishEvent(new ServerStartupEvent(this));
            applicationConfiguration.getName().ifPresent((name) -> {
                ServiceInstance.Builder builder = ServiceInstance.builder(name, getURI());
                ApplicationConfiguration.InstanceConfiguration instance = applicationConfiguration.getInstance();
                instance.getGroup().ifPresent(builder::group);
                instance.getZone().ifPresent(builder::zone);
                builder.metadata(instance.getMetadata());
                instance.getId().ifPresent(builder::instanceId);
                applicationEventPublisher.publishEvent(new ServiceReadyEvent(builder.build()));
            });
        } catch (Exception e) {
            throw new HttpServerException(
                    "Error starting HTTP server: " + e.getMessage(), e
            );
        }
        return this;
    }

    @Override
    public final EmbeddedServer stop() {
        if (isRunning()) {
            try {
                stopServer();
                applicationEventPublisher.publishEvent(new ServerShutdownEvent(this));
                if (applicationContext.isRunning()) {
                    applicationContext.stop();
                }
            } catch (Exception e) {
                throw new HttpServerException(
                        "Error stopping HTTP server: " + e.getMessage(), e
                );
            }
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
     *
     * @throws Exception when an error occurred stopping the server
     */
    protected abstract void stopServer() throws Exception;
}
