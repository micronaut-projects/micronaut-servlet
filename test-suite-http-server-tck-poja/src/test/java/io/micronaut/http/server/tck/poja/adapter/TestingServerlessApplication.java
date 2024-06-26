package io.micronaut.http.server.tck.poja.adapter;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.poja.rawhttp.ServerlessApplication;
import io.micronaut.runtime.ApplicationConfiguration;
import jakarta.inject.Singleton;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.CharBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * An extension of {@link ServerlessApplication} that creates 2
 * pipes to communicate with the server and simplifies reading and writing to them.
 *
 * @author Andriy Dmytruk
 */
@Singleton
@Replaces(ServerlessApplication.class)
public class TestingServerlessApplication extends ServerlessApplication {

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
     * @param applicationContext The application context
     * @param applicationConfiguration The application configuration
     */
    public TestingServerlessApplication(ApplicationContext applicationContext, ApplicationConfiguration applicationConfiguration) {
        super(applicationContext, applicationConfiguration);
    }

    private void createServerSocket() {
        IOException exception = null;
        for (int i = 0; i < 100; ++i) {
            port = new Random().nextInt(10000, 20000);
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
    public TestingServerlessApplication start() {
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
                start(
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
                    socket.getOutputStream().write(response.getBytes());
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
    public @NonNull ServerlessApplication stop() {
        super.stop();
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

    String readInputStream(InputStream inputStream) {
        BufferedReader input = new BufferedReader(new InputStreamReader(inputStream));

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
                if (length < 0 ) {
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

        return result.toString().replace("\r", "");
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

    public int getPort() {
        return port;
    }

}
