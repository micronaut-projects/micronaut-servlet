/*
 * Copyright 2017-2025 original authors
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
import io.micronaut.context.env.CachedEnvironment;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import jakarta.inject.Singleton;

import java.net.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Internal
@Experimental
@Singleton
class HttpServerEmbeddedServer extends AbstractServletServer<HttpServer> {
    private static final String SCHEME_HTTP = "http";
    private final HttpServerConfiguration httpServerConfiguration;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Default constructor.
     *
     * @param applicationConfiguration The application configuration
     * @param server                   The server object
     */
    protected HttpServerEmbeddedServer(HttpServerApplicationContextProvider applicationContextProvider,
                                       ApplicationConfiguration applicationConfiguration,
                                       HttpServerConfiguration httpServerConfiguration,
                                       HttpServer server) {
        super(applicationContextProvider.getApplicationContext(), applicationConfiguration, server);
        this.httpServerConfiguration = httpServerConfiguration;
    }

    @Override
    protected void startServer() throws Exception {
        if (running.compareAndSet(false, true)) {
            HttpServer server = getServer();
            server.start();
        }
    }

    @Override
    protected void stopServer() throws Exception {
        if (running.compareAndSet(true, false)) {
            HttpServer server = getServer();
            server.stop(0);
        }
    }

    @Override
    public int getPort() {
        return getServer().getAddress().getPort();
    }

    @Override
    public String getHost() {
        return httpServerConfiguration.getHost()
            .orElseGet(() -> Optional.ofNullable(CachedEnvironment.getenv(Environment.HOSTNAME)).orElse(SocketUtils.LOCALHOST));
    }

    @Override
    public String getScheme() {
        return SCHEME_HTTP;
    }

    @Override
    public URL getURL() {
        String spec = getScheme() + "://" + getHost() + ":" + getPort();
        try {
            return new URL(spec);
        } catch (MalformedURLException e) {
            throw new HttpServerException("Invalid server URL " + spec);
        }
    }

    @Override
    public URI getURI() {
        try {
            return getURL().toURI();
        } catch (URISyntaxException e) {
            throw new HttpServerException("Invalid server URL " + getURL());
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
