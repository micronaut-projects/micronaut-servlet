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
package io.micronaut.servlet.undertow;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.ApplicationEventPublisher;
import org.jspecify.annotations.Nullable;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.servlet.http.server.AbstractServletServer;
import io.undertow.Undertow;
import io.undertow.server.handlers.GracefulShutdownHandler;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Implementation of {@link AbstractServletServer} for Undertow.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
public class UndertowServer extends AbstractServletServer<Undertow> {

    /**
     * How long to wait for Undertow to end the exchanges of already handled requests; the graceful shutdown grace
     * period bounds the whole wait anyway.
     */
    private static final Duration EXCHANGE_DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private Map<String, Undertow.ListenerInfo> listenersByProtocol = new HashMap<>();

    /**
     * Default constructor.
     * @param applicationContext The app context
     * @param applicationConfiguration The app config
     * @param serverShutdownEventPublisher {@link ApplicationEventPublisher} for the {@link ServerShutdownEvent} event.
     * @param undertow The undertow instance
     */
    @Inject
    public UndertowServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            @Nullable ApplicationEventPublisher<ServerShutdownEvent> serverShutdownEventPublisher,
            Undertow undertow) {
        super(applicationContext, applicationConfiguration, serverShutdownEventPublisher, undertow);
    }

    /**
     * Default constructor.
     * @param applicationContext The app context
     * @param applicationConfiguration The app config
     * @param undertow The undertow instance
     * @deprecated Use {@link #UndertowServer(ApplicationContext, ApplicationConfiguration, ApplicationEventPublisher, Undertow)} instead
     */
    @Deprecated(forRemoval = true, since = "5.2.0")
    public UndertowServer(
        ApplicationContext applicationContext,
        ApplicationConfiguration applicationConfiguration,
        Undertow undertow) {
        this(applicationContext, applicationConfiguration, null, undertow);
    }

    @Override
    protected void startServer() throws Exception {
        Undertow server = getServer();
        server.start();
        this.listenersByProtocol = new HashMap<>();
        for (Undertow.ListenerInfo listenerInfo : server.getListenerInfo()) {
            if (!listenersByProtocol.containsKey(listenerInfo.getProtcol())) {
                listenersByProtocol.put(listenerInfo.getProtcol(), listenerInfo);
            }
        }
    }

    @Override
    protected void stopServer() throws Exception {
        getServer().stop();
    }

    /**
     * Makes the server answer new requests with {@code 503 Service Unavailable} while requests already in progress
     * complete. Undertow's own listener suspension is not used because it closes every connection, including those
     * with a request in flight.
     */
    @Override
    protected void stopAcceptingRequests() {
        shutdownHandler().ifPresent(GracefulShutdownHandler::shutdown);
    }

    /**
     * Waits for the requests the handler counted, and then for Undertow's own exchanges: the handler is done once
     * the response has been handed to the container, but Undertow ends an exchange on its IO thread afterwards,
     * and stopping the server before that drops a response that was written but not yet flushed (seen with
     * {@code Connection: close} requests).
     */
    @Override
    public CompletionStage<?> shutdownGracefully() {
        CompletionStage<?> idle = super.shutdownGracefully();
        GracefulShutdownHandler handler = shutdownHandler().orElse(null);
        if (handler == null) {
            return idle;
        }
        return idle.thenCompose(ignored -> CompletableFuture.runAsync(() -> {
            try {
                handler.awaitShutdown(EXCHANGE_DRAIN_TIMEOUT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private Optional<GracefulShutdownHandler> shutdownHandler() {
        return getApplicationContext().findBean(UndertowFactory.class)
            .map(UndertowFactory::getGracefulShutdownHandler);
    }

    @Override
    public int getPort() {
        Undertow.ListenerInfo https = listenersByProtocol.get("https");
        if (https != null) {
            return ((InetSocketAddress) https.getAddress()).getPort();
        }
        Undertow.ListenerInfo http = listenersByProtocol.get("http");
        if (http != null) {
            return ((InetSocketAddress) http.getAddress()).getPort();
        }
        return -1;
    }

    @Override
    public String getHost() {
        Undertow.ListenerInfo https = listenersByProtocol.get("https");
        if (https != null) {
            return ((InetSocketAddress) https.getAddress()).getHostName();
        }
        Undertow.ListenerInfo http = listenersByProtocol.get("http");
        if (http != null) {
            return ((InetSocketAddress) http.getAddress()).getHostName();
        }
        return "localhost";
    }

    @Override
    public String getScheme() {
        Undertow.ListenerInfo https = listenersByProtocol.get("https");
        if (https != null) {
            return https.getProtcol();
        }
        return "http";
    }

    @Override
    public URL getURL() {
        try {
            return getURI().toURL();
        } catch (MalformedURLException e) {
            throw new InternalServerException(Optional.ofNullable(e.getMessage()).orElse(e.toString()), e);
        }
    }

    @Override
    public URI getURI() {
        try {
            return new URI(getScheme(), null, getHost(), getPort(), null, null, null);
        } catch (URISyntaxException e) {
            throw new InternalServerException(Optional.ofNullable(e.getMessage()).orElse(e.toString()), e);
        }
    }

    @Override
    public boolean isRunning() {
        return getServer().getXnio() != null;
    }
}
