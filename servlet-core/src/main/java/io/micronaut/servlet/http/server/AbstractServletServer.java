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
package io.micronaut.servlet.http.server;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.graceful.GracefulShutdownCapable;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.servlet.http.ServletHttpHandler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Abstract base class for servlet embedded servers.
 *
 * @param <T> The server type
 */
@Internal
public abstract class AbstractServletServer<T> implements EmbeddedServer, GracefulShutdownCapable {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractServletServer.class);

    private final ApplicationContext applicationContext;
    private final ApplicationConfiguration applicationConfiguration;
    @Nullable
    private final ApplicationEventPublisher<ServerShutdownEvent> serverShutdownEventPublisher;
    private final T server;

    /**
     * Default constructor.
     *
     * @param applicationContext       The application context
     * @param applicationConfiguration The application configuration
     * @param serverShutdownEventPublisher {@link ApplicationEventPublisher} for the {@link ServerShutdownEvent} event.
     * @param server                   The server object
     */
    protected AbstractServletServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            @Nullable ApplicationEventPublisher<ServerShutdownEvent> serverShutdownEventPublisher,
            T server) {
        this.applicationContext = applicationContext;
        this.applicationConfiguration = applicationConfiguration;
        this.serverShutdownEventPublisher = serverShutdownEventPublisher;
        this.server = server;
    }

    /**
     * @return The server object.
     */
    public final T getServer() {
        return server;
    }

    /**
     * Shuts the server down gracefully: it stops accepting new requests, then completes once every request that was
     * already in flight has terminated. Called by the {@code GracefulShutdownManager} when
     * {@code micronaut.lifecycle.graceful-shutdown.enabled} is set, before the server is stopped.
     *
     * @return A stage that completes when no request is active
     * @since 6.2.0
     */
    @Override
    public CompletionStage<?> shutdownGracefully() {
        try {
            stopAcceptingRequests();
        } catch (Exception e) {
            LOG.warn("Failed to stop the server accepting requests during graceful shutdown", e);
        }
        return handler().map(ServletHttpHandler::awaitIdle)
            .orElseGet(() -> CompletableFuture.completedFuture(null));
    }

    @Override
    public OptionalLong reportActiveTasks() {
        return handler().map(h -> OptionalLong.of(h.getActiveRequests())).orElseGet(OptionalLong::empty);
    }

    /**
     * Stops the underlying server accepting new requests while leaving in-flight requests to complete. Each
     * container has its own way of doing this; the default does nothing, and the graceful shutdown then only waits
     * for in-flight requests.
     *
     * @throws Exception If the server could not be paused
     * @since 6.2.0
     */
    protected void stopAcceptingRequests() throws Exception {
        // by default there is nothing to pause
    }

    private Optional<ServletHttpHandler<?, ?>> handler() {
        return applicationContext.findBean(ServletHttpHandler.class).map(h -> (ServletHttpHandler<?, ?>) h);
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
        if (isRunning()) {
            try {
                stopServer();
                if (serverShutdownEventPublisher != null) {
                    serverShutdownEventPublisher.publishEvent(new ServerShutdownEvent(this));
                }
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
