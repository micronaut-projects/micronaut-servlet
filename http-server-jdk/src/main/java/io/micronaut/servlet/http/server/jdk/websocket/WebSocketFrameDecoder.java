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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;

import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.CLOSE_INVALID_PAYLOAD;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.CLOSE_NO_STATUS;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.CLOSE_PROTOCOL_ERROR;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.CLOSE_TOO_BIG;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.MAX_CONTROL_PAYLOAD;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.OPCODE_BINARY;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.OPCODE_CLOSE;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.OPCODE_CONTINUATION;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.OPCODE_PING;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.OPCODE_PONG;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.OPCODE_TEXT;

/**
 * Reads the frames a client sends (RFC 6455, 5): every frame masked, no extension, so no
 * reserved bit set. The fragments of a message are put together, a text message is checked to
 * be UTF-8, and a message over the size the session accepts is refused as soon as a frame
 * header announces it, before its payload is read.
 *
 * <p>Not thread-safe: one thread reads a connection.</p>
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
public final class WebSocketFrameDecoder {

    private static final int FIN = 0x80;
    private static final int RSV = 0x70;
    private static final int OPCODE = 0x0F;
    private static final int MASK = 0x80;
    private static final int LENGTH = 0x7F;
    private static final int LENGTH_16 = 126;
    private static final int LENGTH_64 = 127;
    private static final int MASK_KEY_LENGTH = 4;

    private final InputStream in;
    private final IntSupplier maxTextMessageSize;
    private final IntSupplier maxBinaryMessageSize;
    private final byte[] maskKey = new byte[MASK_KEY_LENGTH];
    private final ByteArrayOutputStream message = new ByteArrayOutputStream();
    /**
     * The opcode of the message whose fragments are being read, or {@link WebSocketFrames#OPCODE_CONTINUATION} for none.
     */
    private int messageOpcode = OPCODE_CONTINUATION;

    /**
     * @param in                   The stream of the connection
     * @param maxTextMessageSize   The largest text message, in bytes, read for each message so that it may change
     * @param maxBinaryMessageSize The largest binary message, in bytes, read for each message so that it may change
     */
    public WebSocketFrameDecoder(InputStream in, IntSupplier maxTextMessageSize, IntSupplier maxBinaryMessageSize) {
        this.in = in;
        this.maxTextMessageSize = maxTextMessageSize;
        this.maxBinaryMessageSize = maxBinaryMessageSize;
    }

    /**
     * Reads one frame, and hands the message it completes, or the control frame it is, to the listener.
     *
     * @param listener What receives the messages and the control frames
     * @return {@code false} once the frame read is a close frame
     * @throws WebSocketProtocolException If the client broke the protocol, or sent too much
     * @throws EOFException               If the connection ended without a close frame
     * @throws IOException                If it could not be read
     */
    public boolean readFrame(Listener listener) throws IOException {
        int first = readByte();
        int second = readByte();
        boolean fin = (first & FIN) != 0;
        int opcode = first & OPCODE;
        if ((first & RSV) != 0) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "Reserved bits set without an extension");
        }
        if ((second & MASK) == 0) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "Client frame not masked");
        }
        long length = payloadLength(second & LENGTH);
        boolean control = (opcode & 0x08) != 0;
        if (control) {
            checkControlFrame(opcode, fin, length);
        } else {
            checkDataFrame(opcode, length);
        }
        readFully(maskKey, MASK_KEY_LENGTH);
        byte[] payload = readPayload((int) length);
        return control ? controlFrame(opcode, payload, listener) : dataFrame(opcode, fin, payload, listener);
    }

    private long payloadLength(int length7) throws IOException {
        if (length7 == LENGTH_16) {
            return ((long) readByte() << 8) | readByte();
        }
        if (length7 == LENGTH_64) {
            long length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte();
            }
            if (length < 0) {
                throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "Frame length has its most significant bit set");
            }
            return length;
        }
        return length7;
    }

    private static void checkControlFrame(int opcode, boolean fin, long length) throws WebSocketProtocolException {
        if (opcode != OPCODE_CLOSE && opcode != OPCODE_PING && opcode != OPCODE_PONG) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "Reserved control opcode " + opcode);
        }
        if (!fin) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "Fragmented control frame");
        }
        if (length > MAX_CONTROL_PAYLOAD) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "Control frame payload over 125 bytes");
        }
    }

    private void checkDataFrame(int opcode, long length) throws WebSocketProtocolException {
        int type;
        if (opcode == OPCODE_CONTINUATION) {
            if (messageOpcode == OPCODE_CONTINUATION) {
                throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "Continuation frame without a message");
            }
            type = messageOpcode;
        } else if (opcode == OPCODE_TEXT || opcode == OPCODE_BINARY) {
            if (messageOpcode != OPCODE_CONTINUATION) {
                throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "New message before the previous one ended");
            }
            type = opcode;
        } else {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "Reserved data opcode " + opcode);
        }
        int max = type == OPCODE_TEXT ? maxTextMessageSize.getAsInt() : maxBinaryMessageSize.getAsInt();
        // the limit is the whole message, of which the fragments read so far take part
        long limit = max > 0 ? max : Integer.MAX_VALUE - 8L;
        if (message.size() + length > limit) {
            throw new WebSocketProtocolException(CLOSE_TOO_BIG, "Message over " + limit + " bytes");
        }
    }

    private boolean controlFrame(int opcode, byte[] payload, Listener listener) throws IOException {
        switch (opcode) {
            case OPCODE_PING -> listener.onPing(payload);
            case OPCODE_PONG -> listener.onPong(payload);
            default -> {
                close(payload, listener);
                return false;
            }
        }
        return true;
    }

    private static void close(byte[] payload, Listener listener) throws IOException {
        if (payload.length == 0) {
            listener.onClose(CLOSE_NO_STATUS, "");
            return;
        }
        if (payload.length == 1) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "Close frame with a one byte payload");
        }
        int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        if (!WebSocketFrames.isValidCloseCode(code)) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "Invalid close code " + code);
        }
        listener.onClose(code, utf8(payload, 2));
    }

    private boolean dataFrame(int opcode, boolean fin, byte[] payload, Listener listener) throws IOException {
        if (opcode != OPCODE_CONTINUATION) {
            messageOpcode = opcode;
        }
        if (!fin) {
            message.write(payload, 0, payload.length);
            return true;
        }
        byte[] whole;
        if (message.size() == 0) {
            whole = payload;
        } else {
            message.write(payload, 0, payload.length);
            whole = message.toByteArray();
            message.reset();
        }
        int type = messageOpcode;
        messageOpcode = OPCODE_CONTINUATION;
        if (type == OPCODE_TEXT) {
            listener.onText(utf8(whole, 0));
        } else {
            listener.onBinary(whole);
        }
        return true;
    }

    private static String utf8(byte[] bytes, int offset) throws WebSocketProtocolException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                .toString();
        } catch (CharacterCodingException e) {
            throw new WebSocketProtocolException(CLOSE_INVALID_PAYLOAD, "Text is not UTF-8");
        }
    }

    private byte[] readPayload(int length) throws IOException {
        byte[] payload = new byte[length];
        readFully(payload, length);
        for (int i = 0; i < length; i++) {
            payload[i] ^= maskKey[i & 3];
        }
        return payload;
    }

    private void readFully(byte[] buffer, int length) throws IOException {
        int read = in.readNBytes(buffer, 0, length);
        if (read < length) {
            throw new EOFException("Connection ended inside a frame");
        }
    }

    private int readByte() throws IOException {
        int read = in.read();
        if (read < 0) {
            throw new EOFException("Connection ended without a close frame");
        }
        return read;
    }

    /**
     * Receives what the client sends.
     */
    public interface Listener {

        /**
         * A whole text message.
         *
         * @param text The text
         * @throws IOException If answering it fails
         */
        void onText(String text) throws IOException;

        /**
         * A whole binary message.
         *
         * @param data The data
         * @throws IOException If answering it fails
         */
        void onBinary(byte[] data) throws IOException;

        /**
         * A ping, which is answered with a pong of the same data.
         *
         * @param data The application data
         * @throws IOException If answering it fails
         */
        void onPing(byte[] data) throws IOException;

        /**
         * A pong.
         *
         * @param data The application data
         * @throws IOException If handling it fails
         */
        void onPong(byte[] data) throws IOException;

        /**
         * The close frame of the client.
         *
         * @param code   The close code, {@link WebSocketFrames#CLOSE_NO_STATUS} for a close frame without one
         * @param reason The reason, empty for none
         * @throws IOException If answering it fails
         */
        void onClose(int code, String reason) throws IOException;
    }
}
