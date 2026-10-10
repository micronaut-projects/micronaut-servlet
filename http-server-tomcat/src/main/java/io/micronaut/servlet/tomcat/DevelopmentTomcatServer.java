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
package io.micronaut.servlet.tomcat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.context.reload.RequestAdmission;
import io.micronaut.core.annotation.Internal;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.servlet.http.server.DevelopmentRequestGate;
import io.micronaut.servlet.http.server.DevelopmentSessionStore;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * The {@link TomcatServer} of a generation of the application in development mode, in place of the production one. It
 * runs the Tomcat server kept across restarts by {@link RetainedTomcatServer}, with the contexts of this generation:
 * starting it deploys them in the kept server, and stopping it, or shutting it down gracefully, retires this generation
 * while the kept server, its ports and its executors stay for the next one. It hands the kept server the development
 * launcher's {@link RequestAdmission}, which holds requests while a batch of changes is in progress and sets the hold
 * and drain timeouts. The contexts of this generation that have no session manager of their own get a
 * {@link DevelopmentSessionManager}, which saves their sessions in the {@link DevelopmentSessionStore} as they stop and
 * restores those of the previous generation as they start, unless {@value DevelopmentSessionStore#PERSIST_PROPERTY} is
 * false.
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@Replaces(TomcatServer.class)
@DevelopmentActive
final class DevelopmentTomcatServer extends TomcatServer {

    private final RetainedTomcatServer retained;
    /**
     * The generation the kept server serves while this server runs, or null when it runs a server of its own.
     */
    private volatile DevelopmentRequestGate.@Nullable Generation<List<Context>> generation;

    /**
     * @param applicationContext The application context
     * @param applicationConfiguration The application configuration
     * @param serverShutdownEventPublisher The publisher of the server shutdown event
     * @param tomcat The server this generation built
     * @param retained The server kept across restarts
     * @param sessions The container sessions kept across restarts
     * @param persistSessions Whether the sessions are kept across restarts
     */
    @Inject
    DevelopmentTomcatServer(ApplicationContext applicationContext,
                            ApplicationConfiguration applicationConfiguration,
                            @Nullable ApplicationEventPublisher<ServerShutdownEvent> serverShutdownEventPublisher,
                            Tomcat tomcat,
                            RetainedTomcatServer retained,
                            DevelopmentSessionStore sessions,
                            @Value("${" + DevelopmentSessionStore.PERSIST_PROPERTY + ":true}") boolean persistSessions) {
        super(applicationContext, applicationConfiguration, serverShutdownEventPublisher,
            retained.serve(persistSessions ? withSessions(tomcat, sessions, applicationContext.getClassLoader()) : tomcat));
        this.retained = retained;
        // the launcher's, looked up once per generation: none without a launcher
        retained.admission(RequestAdmission.current());
    }

    /**
     * Has the contexts of the server save and restore their sessions across restarts.
     */
    private static Tomcat withSessions(Tomcat tomcat, DevelopmentSessionStore sessions, ClassLoader classLoader) {
        List<Context> contexts = new ArrayList<>();
        for (Container child : tomcat.getHost().findChildren()) {
            if (child instanceof Context context) {
                contexts.add(context);
            }
        }
        DevelopmentSessionManager.install(contexts, sessions, classLoader);
        return tomcat;
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
        DevelopmentRequestGate.Generation<List<Context>> serving = generation;
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
        DevelopmentRequestGate.Generation<List<Context>> serving = generation;
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
