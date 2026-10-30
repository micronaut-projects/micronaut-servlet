package io.micronaut.servlet.jdk.websocket;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.websocket.OnMessage;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Jakarta session of the JDK runtime: its sends, timeouts and open sessions.
 */
@Timeout(90)
class JdkWebSocketSessionTest {

    private static final String SPEC = "JdkWebSocketSessionTest";

    private static EmbeddedServer server;

    @BeforeAll
    static void start() {
        server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", SPEC,
            "micronaut.servlet.websocket.async-send-timeout", "700ms"
        ));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void theOpenSessionsAreThoseOfTheRouteNotOfTheUrl() throws IOException {
        try (RawClient a = new RawClient(server.getPort()); RawClient b = new RawClient(server.getPort())) {
            a.handshake("/session/room/a");
            b.handshake("/session/room/b");

            a.sendText("count");
            assertEquals("2", a.readText());
        }
    }

    @Test
    void theRequestUriIsComplete() throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            client.handshake("/session/room/a?x=1");

            client.sendText("uri");
            URI uri = URI.create(client.readText());
            assertEquals("ws", uri.getScheme());
            assertEquals("localhost", uri.getHost());
            assertEquals(server.getPort(), uri.getPort());
            assertEquals("/session/room/a", uri.getPath());
            assertEquals("x=1", uri.getQuery());
        }
    }

    @Test
    void aSentBufferIsConsumed() throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            client.handshake("/session/binary");

            client.sendText("go");
            assertEquals(2, client.read().payload().length);
            assertEquals(2, client.read().payload().length);
            assertEquals("basic 0 async 0", client.readText());
        }
    }

    @Test
    void aThrowingSendHandlerDoesNotStopTheSendsAfterIt() throws IOException {
        try (RawClient client = new RawClient(server.getPort())) {
            client.handshake("/session/handler");

            client.sendText("go");
            assertEquals("first", client.readText());
            assertEquals("second", client.readText());
        }
    }

    @Test
    void theAsyncSendTimeoutIsConfiguredAndFailsABlockedSend() throws Exception {
        TimeoutEndpoint.RESULT.set(null);
        try (RawClient client = new RawClient(server.getPort())) {
            client.handshake("/session/timeout");

            // the client reads nothing more: the sends block once the buffers are full
            client.sendText("go");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (TimeoutEndpoint.RESULT.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
                Thread.sleep(20);
            }
        }
        String result = TimeoutEndpoint.RESULT.get();
        assertNotNull(result, "the blocked send failed");
        assertEquals("700 " + SocketTimeoutException.class.getName(), result);
    }

    @Test
    void asyncSendsDoNotNeedAWorkerOfTheServer() throws IOException {
        try (EmbeddedServer single = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", SPEC,
            "micronaut.servlet.enable-virtual-threads", false,
            "micronaut.servlet.max-threads", 1));
             RawClient client = new RawClient(single.getPort())) {
            client.handshake("/session/async");

            client.sendText("hello");
            assertEquals("async hello", client.readText());
        }
    }

    @Test
    void aBlockedWriterDoesNotDelayTheTimeoutsOfTheOtherSessions() throws Exception {
        try (EmbeddedServer idle = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", SPEC,
            "micronaut.servlet.websocket.idle-timeout", "500ms"));
             RawClient flooded = new RawClient(idle.getPort())) {
            flooded.handshake("/session/flood");
            // the client reads nothing: the write of the endpoint blocks, holding the write lock,
            // and the idle timeout of its session tries to close it
            flooded.sendText("go");
            Thread.sleep(1500);

            try (RawClient other = new RawClient(idle.getPort())) {
                other.handshake("/session/room/x");
                long start = System.nanoTime();
                Frame close = other.read();
                assertEquals(0x8, close.opcode());
                assertEquals(1001, close.closeCode());
                assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5);
            }
        }
    }

    private record Frame(int opcode, byte[] payload) {
        int closeCode() {
            return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        }
    }

    private static final class RawClient implements AutoCloseable {
        private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};

        private final Socket socket;
        private final OutputStream out;
        private final DataInputStream in;

        RawClient(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(10_000);
            socket.setReceiveBufferSize(4096);
            out = socket.getOutputStream();
            in = new DataInputStream(socket.getInputStream());
        }

        void handshake(String path) throws IOException {
            String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: localhost:" + socket.getPort() + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = line();
            assertTrue(status.startsWith("HTTP/1.1 101"), status);
            while (!line().isEmpty()) {
                // the headers
            }
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

        void sendText(String text) throws IOException {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);
            frame.write(0x80 | payload.length);
            frame.writeBytes(MASK);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ MASK[i & 3]);
            }
            out.write(frame.toByteArray());
            out.flush();
        }

        String readText() throws IOException {
            Frame frame = read();
            assertEquals(0x1, frame.opcode(), () -> "a text frame, not " + frame.opcode());
            return new String(frame.payload(), StandardCharsets.UTF_8);
        }

        Frame read() throws IOException {
            int first = in.readUnsignedByte();
            int second = in.readUnsignedByte();
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

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Requires(property = "spec.name", value = SPEC)
    @ServerEndpoint("/session/room/{room}")
    public static class RoomEndpoint {
        @OnMessage
        public String onMessage(String message, Session session) {
            return switch (message) {
                case "count" -> String.valueOf(session.getOpenSessions().size());
                case "uri" -> session.getRequestURI().toString();
                default -> message;
            };
        }
    }

    @Requires(property = "spec.name", value = SPEC)
    @ServerEndpoint("/session/binary")
    public static class BinaryEndpoint {
        @OnMessage
        public void onMessage(String message, Session session) throws Exception {
            ByteBuffer basic = ByteBuffer.wrap(new byte[] {1, 2});
            session.getBasicRemote().sendBinary(basic);
            ByteBuffer async = ByteBuffer.wrap(new byte[] {3, 4});
            session.getAsyncRemote().sendBinary(async).get(10, TimeUnit.SECONDS);
            session.getBasicRemote().sendText("basic " + basic.remaining() + " async " + async.remaining());
        }
    }

    @Requires(property = "spec.name", value = SPEC)
    @ServerEndpoint("/session/handler")
    public static class HandlerEndpoint {
        @OnMessage
        public void onMessage(String message, Session session) {
            session.getAsyncRemote().sendText("first", (SendResult result) -> {
                throw new IllegalStateException("a failing handler");
            });
            session.getAsyncRemote().sendText("second");
        }
    }

    @Requires(property = "spec.name", value = SPEC)
    @ServerEndpoint("/session/async")
    public static class AsyncEndpoint {
        @OnMessage
        public void onMessage(String message, Session session) {
            session.getAsyncRemote().sendText("async " + message);
        }
    }

    @Requires(property = "spec.name", value = SPEC)
    @ServerEndpoint("/session/timeout")
    public static class TimeoutEndpoint {
        static final AtomicReference<String> RESULT = new AtomicReference<>();

        @OnMessage
        public void onMessage(String message, Session session) {
            long timeout = session.getAsyncRemote().getSendTimeout();
            ByteBuffer large = ByteBuffer.allocate(1 << 20);
            for (int i = 0; i < 1000; i++) {
                Future<Void> sent = session.getAsyncRemote().sendBinary(large.duplicate());
                try {
                    sent.get(20, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    RESULT.set(timeout + " " + e.getCause().getClass().getName());
                    return;
                } catch (Exception e) {
                    RESULT.set(timeout + " " + e);
                    return;
                }
            }
            RESULT.set(timeout + " never blocked");
        }
    }

    @Requires(property = "spec.name", value = SPEC)
    @ServerEndpoint("/session/flood")
    public static class FloodEndpoint {
        @OnMessage
        public void onMessage(String message, Session session) {
            ByteBuffer large = ByteBuffer.allocate(1 << 20);
            try {
                while (session.isOpen()) {
                    session.getBasicRemote().sendBinary(large.duplicate());
                }
            } catch (IOException | RuntimeException e) {
                // dropped
            }
        }
    }
}
