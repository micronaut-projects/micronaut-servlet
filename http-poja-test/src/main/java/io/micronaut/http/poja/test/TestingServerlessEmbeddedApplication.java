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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.CharBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private static final SecureRandom RANDOM = new SecureRandom();

    private PojaHttpServerlessApplication<?, ?> application;

    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private int port;
    private ServerSocket serverSocket;
    private OutputStream serverInput;
    private InputStream serverOutput;

    private Pipe inputPipe;
    private Pipe outputPipe;
    private Thread serverThread;

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

    private void createServerSocket() {
        IOException exception = null;
        for (int i = 0; i < 100; ++i) {
            port = RANDOM.nextInt(10000, 20000);
            try {
                serverSocket = new ServerSocket(port);
                return;
            } catch (IOException e) {
                exception = e;
            }
        }
        throw new RuntimeException("Could not bind to port " + port, exception);
    }

    @Override
    public TestingServerlessEmbeddedApplication start() {
        if (isRunning.compareAndSet(true, true)) {
            return this; // Already running
        }
        createServerSocket();

        try {
            inputPipe = Pipe.open();
            outputPipe = Pipe.open();
            serverInput = Channels.newOutputStream(inputPipe.sink());
            serverOutput = Channels.newInputStream(outputPipe.source());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Run the request handling on a new thread
        serverThread = new Thread(() -> {
            try {
                application.start(
                    Channels.newInputStream(inputPipe.source()),
                    Channels.newOutputStream(outputPipe.sink())
                );
            } catch (RuntimeException e) {
                // The exception happens since socket is closed when context is destroyed
                if (!(e.getCause() instanceof AsynchronousCloseException)) {
                    throw e;
                }
            }
        });
        serverThread.start();

        // Run the thread that sends requests to the server
        new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    String request = readInputStream(socket.getInputStream());
                    serverInput.write(request.getBytes());
                    serverInput.write(new byte[]{'\n'});

                    String response = readInputStream(serverOutput);
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
                } catch (java.net.SocketException ignored) {
                    // Socket closed
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }).start();

        return this;
    }

    @Override
    public @NonNull TestingServerlessEmbeddedApplication stop() {
        application.stop();
        try {
            serverSocket.close();
            inputPipe.sink().close();
            inputPipe.source().close();
            outputPipe.sink().close();
            outputPipe.source().close();
            serverThread.interrupt();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

    private String readInputStream(InputStream inputStream) {
        // Read with non-UTF charset in case there is binary data and we need to write it back
        BufferedReader input = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.ISO_8859_1));

        StringBuilder result = new StringBuilder();

        boolean body = false;
        int expectedSize = -1;
        int currentSize = 0;
        CharBuffer buffer = CharBuffer.allocate(1024);
        String lastLine = "";

        while (expectedSize < 0 || currentSize < expectedSize) {
            buffer.clear();
            try {
                int length = input.read(buffer);
                if (length < 0) {
                    break;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            buffer.flip();

            List<String> lines = split(buffer.toString());
            for (int i = 0; i < lines.size(); ++i) {
                String line = lines.get(i);
                if (i != 0) {
                    lastLine = line;
                } else {
                    lastLine = lastLine + line;
                }
                if (body) {
                    currentSize += line.length();
                }
                result.append(line);
                if (i < lines.size() - 1) {
                    result.append("\n");
                    if (body) {
                        currentSize += 1;
                    }
                    if (lastLine.toLowerCase(Locale.ENGLISH).startsWith("content-length: ")) {
                        expectedSize = Integer.parseInt(lastLine.substring("content-length: ".length()).trim());
                    }
                    if (lastLine.trim().isEmpty()) {
                        body = true;
                        if (expectedSize < 0) {
                            expectedSize = 0;
                        }
                    }
                }
            }
        }

        return result.toString();
    }

    private List<String> split(String value) {
        // Java split can remove empty lines, so we need this
        List<String> result = new ArrayList<>();
        int startI = 0;
        for (int i = 0; i < value.length(); ++i) {
            if (value.charAt(i) == (char) '\n') {
                result.add(value.substring(startI, i));
                startI = i + 1;
            }
        }
        result.add(value.substring(startI));
        return result;
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
