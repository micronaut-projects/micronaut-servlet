package io.micronaut.http.poja.sample;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
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
            assertTrue(readUntilContains(keepAliveSocket.getInputStream(), "Hello, Micronaut Without Netty!\n")
                .contains("Hello, Micronaut Without Netty!\n"));

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
