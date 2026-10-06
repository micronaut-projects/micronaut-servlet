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
package io.micronaut.servlet.http.server;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.Internal;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The servers that listen on the ports of the routes and the controllers with a port: an {@link HttpServer} listens
 * on a single address. They are started and stopped with the main server.
 *
 * @since 6.2.0
 */
@Internal
@Experimental
@Singleton
final class JdkExposedPortServers {

    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    /**
     * @param server A server on the port of a route
     */
    void add(HttpServer server) {
        servers.add(server);
    }

    /**
     * Starts the servers.
     */
    void start() {
        servers.forEach(HttpServer::start);
    }

    /**
     * Stops the servers.
     */
    void stop() {
        servers.forEach(server -> server.stop(0));
    }
}
