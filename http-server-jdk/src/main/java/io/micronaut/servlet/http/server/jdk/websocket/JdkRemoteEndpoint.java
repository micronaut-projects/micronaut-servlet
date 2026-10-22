/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.servlet.http.server.jdk.websocket;

import io.micronaut.core.annotation.Internal;
import jakarta.websocket.EncodeException;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.OPCODE_BINARY;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.OPCODE_PING;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.OPCODE_PONG;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.OPCODE_TEXT;

/**
 * The remote endpoints of a {@link JdkWebSocketSession}, over its {@link WebSocketConnection}.
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
abstract sealed class JdkRemoteEndpoint implements RemoteEndpoint {

    final JdkWebSocketSession session;
    final WebSocketConnection connection;

    JdkRemoteEndpoint(JdkWebSocketSession session, WebSocketConnection connection) {
        this.session = session;
        this.connection = connection;
    }

    @Override
    public void setBatchingAllowed(boolean allowed) {
        // every frame is written as it is sent: there is nothing to batch
    }

    @Override
    public boolean getBatchingAllowed() {
        return false;
    }

    @Override
    public void flushBatch() {
        // every frame is written as it is sent: there is nothing to flush
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException {
        connection.send(OPCODE_PING, control(applicationData));
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException {
        connection.send(OPCODE_PONG, control(applicationData));
    }

    private static ByteBuffer control(ByteBuffer applicationData) {
        ByteBuffer data = applicationData == null ? ByteBuffer.allocate(0) : applicationData;
        if (data.remaining() > WebSocketFrames.MAX_CONTROL_PAYLOAD) {
            throw new IllegalArgumentException("The application data of a control frame is at most 125 bytes");
        }
        return data;
    }

    final void writeText(String text) throws IOException {
        connection.send(OPCODE_TEXT, WebSocketFrames.utf8(text));
    }

    final void writeBinary(ByteBuffer data) throws IOException {
        connection.send(OPCODE_BINARY, data);
    }

    final void writeObject(Object data) throws IOException, EncodeException {
        switch (data) {
            case String text -> writeText(text);
            case ByteBuffer buffer -> writeBinary(buffer);
            case byte[] bytes -> writeBinary(ByteBuffer.wrap(bytes));
            default -> {
                Object encoded = session.encode(data);
                if (encoded instanceof ByteBuffer buffer) {
                    writeBinary(buffer);
                } else {
                    writeText(encoded.toString());
                }
            }
        }
    }

    /**
     * {@link RemoteEndpoint.Basic}: the send returns once the frame is written.
     */
    static final class Basic extends JdkRemoteEndpoint implements RemoteEndpoint.Basic {

        Basic(JdkWebSocketSession session, WebSocketConnection connection) {
            super(session, connection);
        }

        @Override
        public void sendText(String text, boolean isLast) throws IOException {
            connection.sendPart(OPCODE_TEXT, WebSocketFrames.utf8(text), isLast);
        }

        @Override
        public void sendBinary(ByteBuffer data, boolean isLast) throws IOException {
            connection.sendPart(OPCODE_BINARY, data, isLast);
        }

        @Override
        public OutputStream getSendStream() {
            return new ByteArrayOutputStream() {
                private boolean closed;

                @Override
                public void close() throws IOException {
                    if (!closed) {
                        closed = true;
                        writeBinary(ByteBuffer.wrap(toByteArray()));
                    }
                }
            };
        }

        @Override
        public Writer getSendWriter() {
            return new StringWriter() {
                private boolean closed;

                @Override
                public void close() throws IOException {
                    if (!closed) {
                        closed = true;
                        writeText(toString());
                    }
                }
            };
        }

        @Override
        public void sendText(String text) throws IOException {
            writeText(text);
        }

        @Override
        public void sendBinary(ByteBuffer data) throws IOException {
            writeBinary(data);
        }

        @Override
        public void sendObject(Object data) throws IOException, EncodeException {
            writeObject(data);
        }
    }

    /**
     * {@link RemoteEndpoint.Async}: the send runs on the executor of the server, after the
     * asynchronous sends made before it.
     */
    static final class Async extends JdkRemoteEndpoint implements RemoteEndpoint.Async {

        private volatile long sendTimeout;

        Async(JdkWebSocketSession session, WebSocketConnection connection, long sendTimeout) {
            super(session, connection);
            this.sendTimeout = sendTimeout;
        }

        @Override
        public long getSendTimeout() {
            return sendTimeout;
        }

        @Override
        public void setSendTimeout(long timeoutMillis) {
            this.sendTimeout = timeoutMillis;
        }

        @Override
        public void sendText(String text, SendHandler handler) {
            send(() -> writeText(text), handler);
        }

        @Override
        public Future<Void> sendText(String text) {
            return send(() -> writeText(text));
        }

        @Override
        public Future<Void> sendBinary(ByteBuffer data) {
            return send(() -> writeBinary(data));
        }

        @Override
        public void sendBinary(ByteBuffer data, SendHandler handler) {
            send(() -> writeBinary(data), handler);
        }

        @Override
        public Future<Void> sendObject(Object data) {
            return send(() -> writeObject(data));
        }

        @Override
        public void sendObject(Object data, SendHandler handler) {
            send(() -> writeObject(data), handler);
        }

        private Future<Void> send(Send send) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            send(send, result -> {
                if (result.isOK()) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(result.getException());
                }
            });
            return future;
        }

        private void send(Send send, SendHandler handler) {
            if (handler == null) {
                throw new IllegalArgumentException("No send handler");
            }
            connection.sendAsync(() -> {
                SendResult result;
                try {
                    send.send();
                    result = new SendResult(session);
                } catch (Exception e) {
                    result = new SendResult(session, e);
                }
                handler.onResult(result);
            });
        }
    }

    /**
     * A send that may fail.
     */
    @FunctionalInterface
    private interface Send {
        void send() throws IOException, EncodeException;
    }
}
