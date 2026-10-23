package io.micronaut.http.poja.sample;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@MicronautTest
@Property(name = "spec.name", value = "KeepAliveConnectionTest")
class KeepAliveConnectionTest {

    private static final int SOCKET_TIMEOUT_MILLIS = 2_000;

    @Inject
    EmbeddedServer embeddedServer;

    @Test
    void acceptsSecondConnectionWhileKeepAliveConnectionRemainsOpen() throws IOException {
        try (Socket keepAliveSocket = socket()) {
            writeRequest(keepAliveSocket,
                "GET / HTTP/1.1\r\n" +
                    "Host: h\r\n" +
                    "\r\n");
            String firstResponse = readUntilContains(keepAliveSocket.getInputStream(), "Hello, Micronaut Without Netty!\n");
            assertTrue(firstResponse.contains("Hello, Micronaut Without Netty!\n"));
            assertFalse(firstResponse.toLowerCase(Locale.ROOT).contains("connection: close"));
            assertConnectionStillOpen(keepAliveSocket);

            try (Socket secondSocket = socket()) {
                writeRequest(secondSocket,
                    "PUT /Andriy HTTP/1.1\r\n" +
                        "Host: h\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n");
                assertTrue(readUntilContains(secondSocket.getInputStream(), "Hello, Andriy!\n")
                    .contains("Hello, Andriy!\n"));
            }
        }
    }

    @Test
    void closingOneConnectionDoesNotStopAnother() throws IOException {
        try (Socket first = socket(); Socket second = socket()) {
            String request = "GET / HTTP/1.1\r\nHost: h\r\n\r\n";
            writeRequest(first, request);
            assertTrue(readUntilContains(first.getInputStream(), "Hello, Micronaut Without Netty!\n")
                .contains("Hello, Micronaut Without Netty!\n"));
            writeRequest(second, request);
            assertTrue(readUntilContains(second.getInputStream(), "Hello, Micronaut Without Netty!\n")
                .contains("Hello, Micronaut Without Netty!\n"));

            first.shutdownOutput();
            assertEquals(-1, first.getInputStream().read());

            writeRequest(second, "PUT /Andriy HTTP/1.1\r\nHost: h\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            assertTrue(readUntilContains(second.getInputStream(), "Hello, Andriy!\n")
                .contains("Hello, Andriy!\n"));
        }
    }

    @Test
    void concurrentConnectionsHaveIndependentRequestBodies() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = new ArrayList<Future<?>>();
            for (int connection = 0; connection < 2; connection++) {
                int id = connection;
                results.add(executor.submit(() -> {
                    for (int request = 0; request < 10; request++) {
                        String body = "payload-" + id + "-" + request + ":" + (id == 0 ? "a" : "b").repeat(20_000);
                        try (Socket socket = socket()) {
                            writeRequest(socket, "POST /keep-alive-echo HTTP/1.1\r\nHost: h\r\n" +
                                "Content-Type: text/plain\r\nContent-Length: " + body.length() +
                                "\r\nConnection: close\r\n\r\n" + body);
                            String response = readUntilContains(socket.getInputStream(), body);
                            assertTrue(response.startsWith("HTTP/1.1 200"));
                            assertTrue(response.contains(body));
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> result : results) {
                result.get(15, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stoppingServerClosesKeepAliveConnections() throws IOException {
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class,
            Map.of("spec.name", "KeepAliveConnectionTest"), Environment.TEST);
        try (Socket socket = new Socket(server.getHost(), server.getPort())) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            writeRequest(socket, "GET / HTTP/1.1\r\nHost: h\r\n\r\n");
            assertTrue(readUntilContains(socket.getInputStream(), "Hello, Micronaut Without Netty!\n")
                .contains("Hello, Micronaut Without Netty!\n"));

            server.stop();

            assertFalse(server.isRunning());
            assertEquals(-1, socket.getInputStream().read());
        } finally {
            server.getApplicationContext().close();
        }
    }

    @Controller("/keep-alive-echo")
    @Requires(property = "spec.name", value = "KeepAliveConnectionTest")
    static class EchoController {
        @Post(consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_PLAIN)
        String echo(@Body String body) {
            return body;
        }
    }

    private Socket socket() throws IOException {
        Socket socket = new Socket(embeddedServer.getHost(), embeddedServer.getPort());
        socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        return socket;
    }

    private static void writeRequest(Socket socket, String request) throws IOException {
        OutputStream outputStream = socket.getOutputStream();
        outputStream.write(request.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private static String readUntilContains(InputStream inputStream, String expectedBody) throws IOException {
        byte[] expectedBytes = expectedBody.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        while (true) {
            int read;
            try {
                read = inputStream.read(buffer);
            } catch (SocketTimeoutException e) {
                throw new IOException("Timed out waiting for response body: " + expectedBody, e);
            }
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            if (contains(output.toByteArray(), expectedBytes)) {
                return output.toString(StandardCharsets.UTF_8);
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static void assertConnectionStillOpen(Socket socket) throws IOException {
        try {
            int read = socket.getInputStream().read();
            if (read < 0) {
                fail("Expected keep-alive connection to remain open");
            }
            fail("Expected keep-alive connection to stay open without immediate extra data");
        } catch (SocketTimeoutException expected) {
            // No EOF and no extra data means the connection stayed open.
        }
    }

    private static boolean contains(byte[] bytes, byte[] expectedBytes) {
        outer:
        for (int i = 0; i <= bytes.length - expectedBytes.length; i++) {
            for (int j = 0; j < expectedBytes.length; j++) {
                if (bytes[i + j] != expectedBytes[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
