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
package io.micronaut.servlet.undertow;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.context.reload.RequestAdmission;
import io.micronaut.core.annotation.Internal;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.servlet.http.server.DevelopmentRequestGate;
import io.undertow.Undertow;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletionStage;

/**
 * The {@link UndertowServer} of a generation of the application in development mode, in place of the production one.
 * It runs the Undertow server kept across restarts by {@link RetainedUndertowServer}, with the deployment of this
 * generation: starting it serves the deployment through the kept server, and stopping it, or shutting it down
 * gracefully, retires this generation and undeploys it while the kept server, its ports and its worker stay for the
 * next one. It hands the kept server the development launcher's {@link RequestAdmission}, which holds requests while a
 * batch of changes is in progress and sets the hold and drain timeouts.
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@Replaces(UndertowServer.class)
@DevelopmentActive
final class DevelopmentUndertowServer extends UndertowServer {

    private final RetainedUndertowServer retained;
    /**
     * The generation the kept server serves while this server runs.
     */
    private volatile DevelopmentRequestGate.@Nullable Generation<RetainedUndertowServer.Served> generation;

    /**
     * @param applicationContext The application context
     * @param applicationConfiguration The application configuration
     * @param serverShutdownEventPublisher The publisher of the server shutdown event
     * @param undertow The server the generation runs
     * @param retained The server kept across restarts
     */
    @Inject
    DevelopmentUndertowServer(ApplicationContext applicationContext,
                              ApplicationConfiguration applicationConfiguration,
                              @Nullable ApplicationEventPublisher<ServerShutdownEvent> serverShutdownEventPublisher,
                              Undertow undertow,
                              RetainedUndertowServer retained) {
        super(applicationContext, applicationConfiguration, serverShutdownEventPublisher, undertow);
        this.retained = retained;
        // the launcher's, looked up once per generation: none without a launcher
        retained.admission(RequestAdmission.current());
    }

    @Override
    protected void startServer() throws Exception {
        if (retained.holds(getServer())) {
            generation = retained.start();
        } else {
            super.startServer();
        }
    }

    @Override
    protected void stopServer() throws Exception {
        DevelopmentRequestGate.Generation<RetainedUndertowServer.Served> serving = generation;
        if (serving != null) {
            // the kept server stays for the next generation
            generation = null;
            retained.stop(serving);
            getApplicationContext().findBean(UndertowFactory.class).ifPresent(UndertowFactory::shutdownHandlerExecutor);
        } else {
            super.stopServer();
        }
    }

    @Override
    public CompletionStage<?> shutdownGracefully() {
        DevelopmentRequestGate.Generation<RetainedUndertowServer.Served> serving = generation;
        if (serving != null) {
            // the kept server keeps accepting: the requests that arrive wait for the next generation
            return retained.retire(serving);
        }
        return super.shutdownGracefully();
    }

    @Override
    public boolean isRunning() {
        if (retained.holds(getServer())) {
            return generation != null;
        }
        return super.isRunning();
    }

    @Override
    public int getPort() {
        InetSocketAddress address = address();
        return address == null ? super.getPort() : address.getPort();
    }

    @Override
    public String getHost() {
        InetSocketAddress address = address();
        return address == null ? super.getHost() : address.getHostName();
    }

    @Override
    public String getScheme() {
        if (retained.holds(getServer())) {
            return listener() instanceof Undertow.ListenerInfo info ? info.getProtcol() : "http";
        }
        return super.getScheme();
    }

    /**
     * The listener the server is reached on, the HTTPS one first, as the production server picks it: the kept server
     * started with the first generation, so later generations did not record its listeners.
     */
    private Undertow.@Nullable ListenerInfo listener() {
        Undertow.ListenerInfo http = null;
        for (Undertow.ListenerInfo info : getServer().getListenerInfo()) {
            if ("https".equals(info.getProtcol())) {
                return info;
            }
            if (http == null && "http".equals(info.getProtcol())) {
                http = info;
            }
        }
        return http;
    }

    private @Nullable InetSocketAddress address() {
        if (!retained.holds(getServer()) || getServer().getWorker() == null) {
            return null;
        }
        Undertow.ListenerInfo info = listener();
        return info != null && info.getAddress() instanceof InetSocketAddress address ? address : null;
    }
}
