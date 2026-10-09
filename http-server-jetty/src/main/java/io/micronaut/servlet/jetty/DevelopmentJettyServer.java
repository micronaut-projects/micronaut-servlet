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
package io.micronaut.servlet.jetty;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.context.reload.RequestAdmission;
import io.micronaut.core.annotation.Internal;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.servlet.http.server.DevelopmentRequestGate;
import io.micronaut.web.router.Router;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.jetty.server.Server;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * The {@link JettyServer} of a generation of the application in development mode, in place of the production one. It
 * runs the Jetty server kept across restarts by {@link RetainedJettyServer}, with the handlers of this generation:
 * starting it starts serving this generation through the kept server, and stopping it, or shutting it down gracefully,
 * retires this generation while the kept server, its port and its thread pool stay for the next one. It hands the kept
 * server the development launcher's {@link RequestAdmission}, which holds requests while a batch of changes is in
 * progress and sets the hold and drain timeouts.
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@Replaces(JettyServer.class)
@DevelopmentActive
final class DevelopmentJettyServer extends JettyServer {

    private final RetainedJettyServer retained;
    /**
     * The generation the kept server serves while this server runs, or null when it runs a server of its own.
     */
    private volatile DevelopmentRequestGate.@Nullable Generation<RetainedJettyServer.Served> generation;

    /**
     * @param applicationContext The application context
     * @param applicationConfiguration The application configuration
     * @param server The server this generation built
     * @param router The router
     * @param jettyConfiguration The Jetty configuration
     * @param connectors The connector configurations
     * @param serverShutdownEventPublisher The publisher of the server shutdown event
     * @param retained The server kept across restarts
     */
    @Inject
    DevelopmentJettyServer(ApplicationContext applicationContext,
                           ApplicationConfiguration applicationConfiguration,
                           Server server,
                           Router router,
                           JettyConfiguration jettyConfiguration,
                           List<JettyConfiguration.ConnectorConfiguration> connectors,
                           @Nullable ApplicationEventPublisher<ServerShutdownEvent> serverShutdownEventPublisher,
                           RetainedJettyServer retained) {
        // the production server adds connectors for the ports the routes expose and for connector configurations
        // once built, which a kept, running server must not get again: a server with any is not kept
        super(applicationContext, applicationConfiguration,
            retained.serve(server, router.getExposedPorts().isEmpty() && connectors.isEmpty()),
            router, jettyConfiguration, connectors, serverShutdownEventPublisher);
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
        DevelopmentRequestGate.Generation<RetainedJettyServer.Served> serving = generation;
        if (serving != null) {
            // the kept server stays for the next generation
            generation = null;
            retained.stop(serving);
        } else {
            super.stopServer();
        }
    }

    @Override
    public CompletionStage<?> shutdownGracefully() {
        DevelopmentRequestGate.Generation<RetainedJettyServer.Served> serving = generation;
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
}
