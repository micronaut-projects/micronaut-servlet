package io.micronaut.servlet.jdk.websocket;

import com.sun.net.httpserver.spi.HttpServerProvider;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.servlet.http.server.jdk.websocket.JdkServerContainer;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;
import jakarta.websocket.server.ServerContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 6455 on the wire, against the codec and the session of the JDK runtime, with the
 * HttpServer provider of io.github.robaho:httpserver.
 */
@Timeout(60)
class JdkWebSocketProtocolTest {

    private static final String SPEC = "JdkWebSocketProtocolTest";
    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    private static EmbeddedServer server;

    @BeforeAll
    static void start() {
        server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", SPEC,
            "micronaut.servlet.websocket.max-text-message-size", 1024
        ));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void theServerRunsOnTheProviderThatUpgrades() {
        assertTrue(HttpServerProvider.provider().getClass().getName().startsWith("robaho."));
        assertInstanceOf(JdkServerContainer.class, server.getApplicationContext().getBean(ServerContainer.class));
    }

    @Test
    void theHandshakeAnswersTheKeyAndNegotiatesASubprotocol() throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            Map<String, String> response = client.handshake("/protocol/echo", Map.of("Sec-WebSocket-Protocol", "other, v2"));

            assertEquals("HTTP/1.1 101 Switching Protocols", response.get(":status"));
            assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", response.get("sec-websocket-accept"));
            assertEquals("v2", response.get("sec-websocket-protocol"));
            assertEquals("websocket", response.get("upgrade").toLowerCase(Locale.ROOT));
            assertNull(response.get("sec-websocket-extensions"));
        }
    }

    @Test
    void anUnknownVersionIsAnUpgradeRequired() throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            Map<String, String> response = client.handshake("/protocol/echo", Map.of("Sec-WebSocket-Version", "8"));

            assertTrue(response.get(":status").startsWith("HTTP/1.1 426"), response.toString());
            assertEquals("13", response.get("sec-websocket-version"));
        }
    }

    @Test
    void anInvalidKeyIsABadRequest() throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            Map<String, String> response = client.handshake("/protocol/echo", Map.of("Sec-WebSocket-Key", "c2hvcnQ="));

            assertTrue(response.get(":status").startsWith("HTTP/1.1 400"), response.toString());
        }
    }

    @Test
    void echoesWholeAndFragmentedMessagesAndAnswersPings() throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            client.handshake("/protocol/echo", Map.of());

            client.send(true, 0x1, "hello".getBytes(StandardCharsets.UTF_8));
            assertFrame(0x1, "hello", client.read());

            client.send(false, 0x1, "frag".getBytes(StandardCharsets.UTF_8));
            client.send(true, 0x9, "ping-data".getBytes(StandardCharsets.UTF_8));
            client.send(true, 0x0, "mented".getBytes(StandardCharsets.UTF_8));
            assertFrame(0xA, "ping-data", client.read());
            assertFrame(0x1, "fragmented", client.read());

            byte[] large = new byte[1000];
            Arrays.fill(large, (byte) 'x');
            client.send(true, 0x1, large);
            assertFrame(0x1, new String(large, StandardCharsets.UTF_8), client.read());
        }
    }

    @Test
    void echoesBinaryMessages() throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            client.handshake("/protocol/binary", Map.of());

            client.send(false, 0x2, new byte[] {1, 2});
            client.send(true, 0x0, new byte[] {3});

            Frame binary = client.read();
            assertEquals(0x2, binary.opcode());
            assertArrayEquals(new byte[] {1, 2, 3}, binary.payload());
        }
    }

    @Test
    void theCloseOfTheClientIsAnsweredAndEndsTheConnection() throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            client.handshake("/protocol/echo", Map.of());

            client.send(true, 0x8, closePayload(1000, "done"));

            Frame close = client.read();
            assertEquals(0x8, close.opcode());
            assertEquals(1000, close.closeCode());
            client.assertEnded();
        }
    }

    @Test
    void theServerClosesAndWaitsForTheClose() throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            client.handshake("/protocol/echo", Map.of());

            client.send(true, 0x1, "bye".getBytes(StandardCharsets.UTF_8));

            Frame close = client.read();
            assertEquals(0x8, close.opcode());
            assertEquals(1000, close.closeCode());
            client.send(true, 0x8, closePayload(1000, ""));
            client.assertEnded();
        }
    }

    @Test
    void invalidUtf8IsClosedWith1007() throws IOException {
        assertClosedWith(1007, 0x1, new byte[] {(byte) 0xc3, 0x28});
    }

    @Test
    void anUnmaskedFrameIsClosedWith1002() throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            client.handshake("/protocol/echo", Map.of());

            client.out.write(new byte[] {(byte) 0x81, 0x02, 'h', 'i'});
            client.out.flush();

            Frame close = client.read();
            assertEquals(0x8, close.opcode());
            assertEquals(1002, close.closeCode());
            client.assertEnded();
        }
    }

    @Test
    void aMessageOverTheLimitIsClosedWith1009() throws IOException {
        assertClosedWith(1009, 0x1, new byte[2000]);
    }

    @Test
    void anIdleSessionIsClosedWith1001() throws IOException {
        try (EmbeddedServer idle = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", SPEC,
            "micronaut.servlet.websocket.idle-timeout", "300ms"));
             RawClient client = new RawClient(idle.getPort())) {
            client.handshake("/protocol/echo", Map.of());

            Frame close = client.read();

            assertEquals(0x8, close.opcode());
            assertEquals(1001, close.closeCode());
        }
    }

    private static void assertClosedWith(int code, int opcode, byte[] payload) throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            client.handshake("/protocol/echo", Map.of());

            client.send(true, opcode, payload);

            Frame close = client.read();
            assertEquals(0x8, close.opcode());
            assertEquals(code, close.closeCode());
            client.assertEnded();
        }
    }

    private static void assertFrame(int opcode, String text, Frame frame) {
        assertEquals(opcode, frame.opcode());
        assertEquals(text, new String(frame.payload(), StandardCharsets.UTF_8));
    }

    private static byte[] closePayload(int code, String reason) {
        byte[] text = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + text.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(text, 0, payload, 2, text.length);
        return payload;
    }

    private record Frame(int opcode, byte[] payload) {
        int closeCode() {
            return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        }
    }

    /**
     * A client of raw frames, which sends what a WebSocket client would not.
     */
    private static final class RawClient implements AutoCloseable {
        private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};

        private final Socket socket;
        private final OutputStream out;
        private final DataInputStream in;

        RawClient(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(10_000);
            out = socket.getOutputStream();
            in = new DataInputStream(socket.getInputStream());
        }

        Map<String, String> handshake(String path, Map<String, String> headers) throws IOException {
            Map<String, String> request = new LinkedHashMap<>();
            request.put("Host", "localhost:" + socket.getPort());
            request.put("Upgrade", "websocket");
            request.put("Connection", "Upgrade");
            request.put("Sec-WebSocket-Key", KEY);
            request.put("Sec-WebSocket-Version", "13");
            request.putAll(headers);
            StringBuilder text = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n");
            request.forEach((name, value) -> text.append(name).append(": ").append(value).append("\r\n"));
            text.append("\r\n");
            out.write(text.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Map<String, String> response = new LinkedHashMap<>();
            response.put(":status", line());
            for (String line = line(); !line.isEmpty(); line = line()) {
                int colon = line.indexOf(':');
                response.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            return response;
        }

        private String line() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int b = in.read(); b != '\n'; b = in.read()) {
                if (b < 0) {
                    throw new EOFException();
                }
                if (b != '\r') {
                    line.write(b);
                }
            }
            return line.toString(StandardCharsets.ISO_8859_1);
        }

        void send(boolean fin, int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write((fin ? 0x80 : 0) | opcode);
            if (payload.length <= 125) {
                frame.write(0x80 | payload.length);
            } else {
                frame.write(0x80 | 126);
                frame.write(payload.length >>> 8);
                frame.write(payload.length);
            }
            frame.writeBytes(MASK);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ MASK[i & 3]);
            }
            out.write(frame.toByteArray());
            out.flush();
        }

        Frame read() throws IOException {
            int first = in.readUnsignedByte();
            int second = in.readUnsignedByte();
            assertEquals(0, second & 0x80, "a server frame is not masked");
            assertEquals(0x80, first & 0x80, "a server frame is whole");
            long length = second & 0x7F;
            if (length == 126) {
                length = in.readUnsignedShort();
            } else if (length == 127) {
                length = in.readLong();
            }
            byte[] payload = new byte[(int) length];
            in.readFully(payload);
            return new Frame(first & 0x0F, payload);
        }

        /**
         * The server closed the connection: the end of the stream, or a reset when it closed with
         * data of the client it did not read, e.g. a message over the limit.
         */
        void assertEnded() {
            assertThrows(IOException.class, () -> {
                InputStream input = in;
                if (input.read() < 0) {
                    throw new EOFException();
                }
            });
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Requires(property = "spec.name", value = SPEC)
    @ServerWebSocket(value = "/protocol/echo", subprotocols = "chat, v2")
    static class EchoServerWebSocket {
        @OnMessage
        void echo(String message, WebSocketSession session) {
            if ("bye".equals(message)) {
                session.close();
            } else {
                session.sendSync(message);
            }
        }
    }

    @Requires(property = "spec.name", value = SPEC)
    @ServerWebSocket("/protocol/binary")
    static class BinaryEchoServerWebSocket {
        @OnMessage
        void echo(byte[] message, WebSocketSession session) {
            session.sendSync(message);
        }
    }
}
