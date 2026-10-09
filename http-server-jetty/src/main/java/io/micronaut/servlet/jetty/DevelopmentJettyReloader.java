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

import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.core.annotation.Internal;
import jakarta.inject.Singleton;
import org.eclipse.jetty.server.Server;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * What a generation of the application does with the Jetty server kept across restarts in development mode: it
 * stages its handlers in it, starts serving through it, and stops serving through it, while the server, its port and
 * its thread pool stay for the next generation. A bean of each generation, so that the kept server holds nothing of
 * one, and reads the hold and drain timeouts of the development runtime from the generation's configuration.
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@DevelopmentActive
final class DevelopmentJettyReloader {

    /**
     * How long a request that arrives while the application restarts waits for the next generation.
     */
    static final String HOLD_TIMEOUT = "micronaut.dev.requests.hold-timeout";
    /**
     * How long a restart waits for the requests in flight on the stopping generation.
     */
    static final String DRAIN_TIMEOUT = "micronaut.dev.requests.drain-timeout";
    static final Duration DEFAULT_HOLD_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private final DevelopmentJettyServer server;

    /**
     * @param server The server kept across restarts
     * @param hold How long a request waits for the next generation
     * @param drain How long a restart waits for the requests in flight
     */
    DevelopmentJettyReloader(DevelopmentJettyServer server,
                             @Value("${" + HOLD_TIMEOUT + ":30s}") Duration hold,
                             @Value("${" + DRAIN_TIMEOUT + ":10s}") Duration drain) {
        this.server = server;
        server.timeouts(hold, drain);
    }

    /**
     * Creates the server with the loader of the kept server's classes as the context class loader: what the server
     * creates keeps the context class loader of the creating thread, as its scheduler, which starts its thread with it,
     * and the server may be kept across restarts, where a generation's loader would keep that generation.
     *
     * @param factory Creates the server
     * @return The server
     */
    Server create(Supplier<Server> factory) {
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        thread.setContextClassLoader(DevelopmentJettyReloader.class.getClassLoader());
        try {
            return factory.get();
        } finally {
            thread.setContextClassLoader(loader);
        }
    }

    /**
     * The server to run: the kept one, with the generation's handlers staged in it, or the generation's own.
     *
     * @param built The server the generation built, not started
     * @return The server to run
     */
    Server serve(Server built) {
        return server.serve(built);
    }

    /**
     * Whether the given server is the one kept across restarts.
     *
     * @param candidate The server
     * @return True if kept
     */
    boolean holds(Server candidate) {
        return server.holds(candidate);
    }

    /**
     * Starts serving the generation through the kept server.
     *
     * @param running The server the generation runs
     * @return The generation, or null when the generation runs a server of its own
     * @throws Exception If the server or the generation's handlers cannot start
     */
    DevelopmentJettyServer.@Nullable Generation start(Server running) throws Exception {
        return server.holds(running) ? server.start() : null;
    }

    /**
     * The generation stops serving: new requests wait for the next one.
     *
     * @param generation The generation
     * @return A future that completes once no request of the generation is in flight
     */
    CompletableFuture<Void> retire(DevelopmentJettyServer.Generation generation) {
        return server.retire(generation);
    }

    /**
     * Stops the generation's handlers once its requests finished, or after the drain timeout; the server keeps running.
     *
     * @param generation The generation
     */
    void stop(DevelopmentJettyServer.Generation generation) {
        server.stop(generation);
    }
}
