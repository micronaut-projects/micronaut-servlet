/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.http.poja.test;

import io.micronaut.http.poja.PojaHttpServerlessApplication;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An embedded server that uses {@link PojaHttpServerlessApplication} as application.
 * It can be used for testing POJA serverless applications the same way a normal micronaut
 * server would be tested.
 *
 * <p>It delegates to {@link io.micronaut.http.poja.PojaHttpServerlessApplication} by creating 2
 * pipes to communicate with the client and simplifies reading and writing to them.</p>
 *
 * @author Andriy Dmytruk
 */
@Singleton
@Requires(env = Environment.TEST)
@Replaces(EmbeddedApplication.class)
public class TestingServerlessEmbeddedApplication implements EmbeddedServer {

    private PojaHttpServerlessApplication<?, ?> application;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Set<Socket> activeConnections = ConcurrentHashMap.newKeySet();
    private @Nullable ServerSocket serverSocket;
    private @Nullable ExecutorService connectionExecutor;
    private int port;

    /**
     * Default constructor.
     *
     * @param application The application context
     */
    public TestingServerlessEmbeddedApplication(
        PojaHttpServerlessApplication<?, ?> application
    ) {
        this.application = application;
    }

    private ServerSocket createServerSocket() {
        try {
            return new ServerSocket(0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not bind", e);
        }
    }

    @Override
    public TestingServerlessEmbeddedApplication start() {
        if (isRunning.getAndSet(true)) {
            return this; // Already running
        }
        ServerSocket serverSocket = createServerSocket();
        this.serverSocket = serverSocket;
        port = serverSocket.getLocalPort();
        ExecutorService connectionExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        this.connectionExecutor = connectionExecutor;

        // Run the thread that sends requests to the server
        Thread acceptThread = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    activeConnections.add(socket);
                    connectionExecutor.execute(() -> handleConnection(socket));
                } catch (java.net.SocketException ignored) {
                    // Socket closed
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        acceptThread.setDaemon(true);
        acceptThread.start();

        return this;
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            application.start(socket.getInputStream(), socket.getOutputStream());
        } catch (java.net.SocketException ignored) {
            // Socket closed
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            activeConnections.remove(socket);
        }
    }

    @Override
    public @NonNull TestingServerlessEmbeddedApplication stop() {
        isRunning.set(false);
        application.stop();
        for (Socket connection : activeConnections) {
            try {
                connection.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        activeConnections.clear();
        ExecutorService connectionExecutor = this.connectionExecutor;
        if (connectionExecutor != null) {
            connectionExecutor.shutdownNow();
            connectionExecutor = null;
        }
        this.connectionExecutor = null;
        ServerSocket serverSocket = this.serverSocket;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        this.serverSocket = null;
        return this;
    }

    @Override
    public boolean isRunning() {
        return isRunning.get();
    }

    /**
     * Get the port.
     *
     * @return The port
     */
    @Override
    public int getPort() {
        return port;
    }

    @Override
    public String getHost() {
        return "localhost";
    }

    @Override
    public String getScheme() {
        return "http";
    }

    @Override
    public URL getURL() {
        try {
            return getURI().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public URI getURI() {
        return URI.create("http://localhost:" + getPort());
    }

    @Override
    public ApplicationContext getApplicationContext() {
        return application.getApplicationContext();
    }

    @Override
    public ApplicationConfiguration getApplicationConfiguration() {
        return application.getApplicationConfiguration();
    }
}
