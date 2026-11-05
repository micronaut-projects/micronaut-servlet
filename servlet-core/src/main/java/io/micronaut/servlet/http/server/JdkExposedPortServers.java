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

import java.io.IOException;
import java.net.InetSocketAddress;
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

    private final List<PortServer> servers = new CopyOnWriteArrayList<>();
    private final List<HttpServer> started = new CopyOnWriteArrayList<>();

    /**
     * @param server  An unbound server on the port of a route
     * @param address The address the server listens on once started
     */
    void add(HttpServer server, InetSocketAddress address) {
        servers.add(new PortServer(server, address));
    }

    /**
     * Binds and starts the servers. They are bound on start rather than when they are created, so that a port
     * already in use fails the start of the embedded server, as for the servlet containers.
     *
     * @throws IOException if a server cannot be bound, such as a {@link java.net.BindException}
     */
    void start() throws IOException {
        for (PortServer portServer : servers) {
            portServer.server().bind(portServer.address(), 0);
            portServer.server().start();
            started.add(portServer.server());
        }
    }

    /**
     * Stops the servers that were started.
     */
    void stop() {
        started.forEach(server -> server.stop(0));
        started.clear();
    }

    private record PortServer(HttpServer server, InetSocketAddress address) {
    }
}
