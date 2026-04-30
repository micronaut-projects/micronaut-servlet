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
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.server.HttpServerConfiguration;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Factory for creating beans of type {@link HttpServer}.
 */
@Experimental
@Internal
@Factory
@Requires(property = "micronaut.server.jdk.enabled", value = "true", defaultValue = "true")
public class HttpServerFactory {
    /**
     *
     * @param applicationContext Application Context
     * @param httpServerConfiguration HTTP Server Configuration
     * @param httpHandlers Handlers
     * @return An HTTP Server
     * @throws IOException If an error occurs creating the server
     */
    @Requires(beans = HttpHandlerPath.class)
    @Singleton
    HttpServer createHttpServer(ApplicationContext applicationContext,
                                HttpServerConfiguration httpServerConfiguration,
                                List<HttpHandlerPath> httpHandlers) throws IOException {
        HttpServer server = HttpServer.create(serverAddress(applicationContext, httpServerConfiguration), 0);
        for (HttpHandlerPath handler : httpHandlers) {
            server.createContext(handler.getPath(), handler.getHttpHandler());
        }
        return server;
    }

    /**
     *
     * @param applicationContext Application Context
     * @param httpServerConfiguration HTTP Server Configuration
     * @return Server address to listen on
     */
    private InetSocketAddress serverAddress(ApplicationContext applicationContext,
                     HttpServerConfiguration httpServerConfiguration) {
        ServerPort serverPort = ServerPort.of(httpServerConfiguration,
            applicationContext.getEnvironment().getActiveNames());
        return new InetSocketAddress(serverPort.port());
    }
}
