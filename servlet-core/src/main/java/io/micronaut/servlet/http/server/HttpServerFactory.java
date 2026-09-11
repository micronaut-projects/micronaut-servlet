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
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.http.ssl.ServerSslConfiguration;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.scheduling.LoomSupport;
import io.micronaut.servlet.http.ServletConfiguration;
import jakarta.inject.Singleton;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Factory for creating beans of type {@link HttpServer}.
 */
@Experimental
@Internal
@Factory
@Requires(property = "micronaut.server.jdk.enabled", value = "true", defaultValue = "true")
public class HttpServerFactory {
    /**
     * Thread count used when virtual threads are unavailable or disabled and no maximum is configured. Matches the
     * default the servlet containers use.
     */
    private static final int DEFAULT_MAX_THREADS = 200;

    /**
     * Queued requests allowed per worker before the dispatcher thread starts running them itself.
     */
    private static final int QUEUE_DEPTH_PER_THREAD = 4;

    /**
     * How long an idle worker is kept before it is retired.
     */
    private static final long THREAD_KEEP_ALIVE_SECONDS = 60L;

    /**
     *
     * @param applicationContext Application Context
     * @param httpServerConfiguration HTTP Server Configuration
     * @param servletConfiguration Servlet Configuration
     * @param executorOwnership Records the executor created here, so only that one is shut down with the server
     * @param httpHandlers Handlers
     * @return An HTTP Server
     * @throws IOException If an error occurs creating the server
     */
    @Requires(beans = HttpHandlerPath.class)
    @Singleton
    HttpServer createHttpServer(ApplicationContext applicationContext,
                                HttpServerConfiguration httpServerConfiguration,
                                ServletConfiguration servletConfiguration,
                                ServerSslConfiguration sslConfiguration,
                                ResourceResolver resourceResolver,
                                JdkServerExecutorOwnership executorOwnership,
                                List<HttpHandlerPath> httpHandlers) throws IOException {
        HttpServer server = sslConfiguration.isEnabled()
            ? createHttpsServer(applicationContext, sslConfiguration, resourceResolver)
            : HttpServer.create(serverAddress(applicationContext, httpServerConfiguration), 0);
        ExecutorService executorService = createExecutor(servletConfiguration);
        executorOwnership.owns(executorService);
        server.setExecutor(executorService);
        for (HttpHandlerPath handler : httpHandlers) {
            server.createContext(handler.getPath(), handler.getHttpHandler());
        }
        return server;
    }

    /**
     * Creates a TLS server on the SSL port.
     *
     * <p>A {@link HttpServer} listens on a single address, so when TLS is enabled the server is HTTPS only, as the
     * Netty server is; there is no additional plain-text port. In the test environment the default SSL port is
     * replaced by a random one, as the servlet containers do.</p>
     *
     * @param applicationContext The application context
     * @param sslConfiguration   The SSL configuration
     * @param resourceResolver   Resolves the key and trust stores
     * @return The HTTPS server
     * @throws IOException If the server cannot be bound
     */
    private HttpsServer createHttpsServer(ApplicationContext applicationContext,
                                          ServerSslConfiguration sslConfiguration,
                                          ResourceResolver resourceResolver) throws IOException {
        int port = sslConfiguration.getPort();
        if (port == SslConfiguration.DEFAULT_PORT && applicationContext.getEnvironment().getActiveNames().contains(Environment.TEST)) {
            port = 0;
        }
        SSLContext sslContext = new JdkSslContextBuilder(resourceResolver).build(sslConfiguration)
            .orElseThrow(() -> new HttpServerException("SSL is enabled but no SSL context could be built"));
        HttpsServer server = HttpsServer.create(new InetSocketAddress(port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters parameters = getSSLContext().getDefaultSSLParameters();
                sslConfiguration.getProtocols().ifPresent(parameters::setProtocols);
                sslConfiguration.getCiphers().ifPresent(parameters::setCipherSuites);
                sslConfiguration.getClientAuthentication().ifPresent(clientAuthentication -> {
                    switch (clientAuthentication) {
                        case NEED -> parameters.setNeedClientAuth(true);
                        case WANT -> parameters.setWantClientAuth(true);
                        default -> {
                            // no client authentication
                        }
                    }
                });
                params.setSSLParameters(parameters);
            }
        });
        return server;
    }

    /**
     * Creates the executor that runs request handlers.
     *
     * <p>{@link HttpServer} runs every handler on its own dispatcher thread when no executor is set, so a single
     * request that waits blocks every other request on the server. Give it a real pool: virtual threads when they
     * are available and enabled, otherwise a bounded platform pool.</p>
     *
     * <p>The pool is closed by {@link HttpServerEmbeddedServer} when the server stops.</p>
     *
     * @param servletConfiguration The servlet configuration
     * @return The executor to run handlers on
     */
    private ExecutorService createExecutor(ServletConfiguration servletConfiguration) {
        if (servletConfiguration.isEnableVirtualThreads() && LoomSupport.isSupported()) {
            return Executors.newThreadPerTaskExecutor(
                LoomSupport.newVirtualThreadFactory("micronaut-jdk-server-", builder -> { })
            );
        }
        Integer configuredMaxThreads = servletConfiguration.getMaxThreads();
        int maxThreads = configuredMaxThreads != null && configuredMaxThreads > 0 ? configuredMaxThreads : DEFAULT_MAX_THREADS;
        // a fixed pool queues without limit, so handlers that block let exchanges pile up until the heap is gone.
        // Bound the queue and let the dispatcher thread run the overflow, which stops it accepting for as long as
        // the work takes and so pushes back on the client rather than buffering the burst
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            maxThreads,
            maxThreads,
            THREAD_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(maxThreads * QUEUE_DEPTH_PER_THREAD),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
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
