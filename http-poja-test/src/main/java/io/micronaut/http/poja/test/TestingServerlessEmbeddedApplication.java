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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.poja.PojaHttpServerlessApplication;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;

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
import java.util.concurrent.RejectedExecutionException;
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
    private ServerSocket serverSocket;
    private ExecutorService connectionExecutor;
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
        ServerSocket createdServerSocket = createServerSocket();
        this.serverSocket = createdServerSocket;
        port = createdServerSocket.getLocalPort();
        ExecutorService createdConnectionExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        this.connectionExecutor = createdConnectionExecutor;

        // Run the thread that sends requests to the server
        Thread acceptThread = new Thread(() -> {
            while (!createdServerSocket.isClosed()) {
                try {
                    Socket socket = createdServerSocket.accept();
                    activeConnections.add(socket);
                    if (!isRunning.get()) {
                        activeConnections.remove(socket);
                        socket.close();
                        continue;
                    }
                    try {
                        createdConnectionExecutor.execute(() -> handleConnection(socket));
                    } catch (RejectedExecutionException e) {
                        activeConnections.remove(socket);
                        socket.close();
                        if (!createdServerSocket.isClosed()) {
                            throw e;
                        }
                    }
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
            application.handleConnection(socket.getInputStream(), socket.getOutputStream());
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
        if (!isRunning.getAndSet(false)) {
            return this;
        }
        try {
            ServerSocket currentServerSocket = this.serverSocket;
            if (currentServerSocket != null) {
                currentServerSocket.close();
            }
            this.serverSocket = null;
            for (Socket connection : activeConnections) {
                connection.close();
            }
            activeConnections.clear();
            ExecutorService currentConnectionExecutor = this.connectionExecutor;
            if (currentConnectionExecutor != null) {
                currentConnectionExecutor.shutdownNow();
            }
            this.connectionExecutor = null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            application.stop();
        }
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
