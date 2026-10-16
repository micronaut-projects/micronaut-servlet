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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The frames of RFC 6455 a server writes: never masked, and never compressed, since no
 * extension is negotiated.
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
public final class WebSocketFrames {

    /**
     * A continuation frame.
     */
    public static final int OPCODE_CONTINUATION = 0x0;
    /**
     * A text frame.
     */
    public static final int OPCODE_TEXT = 0x1;
    /**
     * A binary frame.
     */
    public static final int OPCODE_BINARY = 0x2;
    /**
     * A close frame.
     */
    public static final int OPCODE_CLOSE = 0x8;
    /**
     * A ping frame.
     */
    public static final int OPCODE_PING = 0x9;
    /**
     * A pong frame.
     */
    public static final int OPCODE_PONG = 0xA;

    /**
     * The close code of a normal closure.
     */
    public static final int CLOSE_NORMAL = 1000;
    /**
     * The close code of an endpoint that goes away, e.g. on an idle timeout.
     */
    public static final int CLOSE_GOING_AWAY = 1001;
    /**
     * The close code of a protocol error.
     */
    public static final int CLOSE_PROTOCOL_ERROR = 1002;
    /**
     * The close code of a message of a type the endpoint cannot accept.
     */
    public static final int CLOSE_CANNOT_ACCEPT = 1003;
    /**
     * The close code a close frame without a payload stands for. It is never sent.
     */
    public static final int CLOSE_NO_STATUS = 1005;
    /**
     * The close code of a connection that ended without a close frame. It is never sent.
     */
    public static final int CLOSE_ABNORMAL = 1006;
    /**
     * The close code of a text message that is not UTF-8.
     */
    public static final int CLOSE_INVALID_PAYLOAD = 1007;
    /**
     * The close code of a message over the size the endpoint accepts.
     */
    public static final int CLOSE_TOO_BIG = 1009;
    /**
     * The close code of an unexpected condition of the server.
     */
    public static final int CLOSE_INTERNAL_ERROR = 1011;

    /**
     * The largest payload of a control frame.
     */
    public static final int MAX_CONTROL_PAYLOAD = 125;

    private static final int FIN = 0x80;
    private static final int LENGTH_16 = 126;
    private static final int LENGTH_64 = 127;
    private static final int MAX_LENGTH_7 = 125;
    private static final int MAX_LENGTH_16 = 0xFFFF;
    private static final int MAX_REASON_BYTES = MAX_CONTROL_PAYLOAD - 2;

    private WebSocketFrames() {
    }

    /**
     * Writes one frame. The caller serializes the writes of a connection and flushes.
     *
     * @param out     Where to
     * @param fin     Whether this is the last frame of the message
     * @param opcode  The opcode
     * @param payload The payload, which is not consumed
     * @throws IOException If it could not be written
     */
    public static void write(OutputStream out, boolean fin, int opcode, ByteBuffer payload) throws IOException {
        int length = payload.remaining();
        out.write(header(fin, opcode, length));
        if (payload.hasArray()) {
            out.write(payload.array(), payload.arrayOffset() + payload.position(), length);
        } else {
            byte[] bytes = new byte[length];
            payload.duplicate().get(bytes);
            out.write(bytes);
        }
    }

    /**
     * The header of an unmasked frame.
     *
     * @param fin    Whether this is the last frame of the message
     * @param opcode The opcode
     * @param length The length of the payload
     * @return The header
     */
    static byte[] header(boolean fin, int opcode, int length) {
        int first = (fin ? FIN : 0) | (opcode & 0x0F);
        if (length <= MAX_LENGTH_7) {
            return new byte[] {(byte) first, (byte) length};
        }
        if (length <= MAX_LENGTH_16) {
            return new byte[] {(byte) first, (byte) LENGTH_16, (byte) (length >>> 8), (byte) length};
        }
        byte[] header = new byte[10];
        header[0] = (byte) first;
        header[1] = (byte) LENGTH_64;
        long value = length;
        for (int i = 9; i >= 2; i--) {
            header[i] = (byte) value;
            value >>>= 8;
        }
        return header;
    }

    /**
     * The payload of a close frame: the code and as much of the reason as fits a control frame,
     * cut at a character so that it stays UTF-8.
     *
     * @param code   The close code, or {@link #CLOSE_NO_STATUS} for an empty payload
     * @param reason The reason, if any
     * @return The payload
     */
    public static ByteBuffer closePayload(int code, String reason) {
        if (code == CLOSE_NO_STATUS) {
            return ByteBuffer.allocate(0);
        }
        ByteBuffer payload = ByteBuffer.allocate(2 + MAX_REASON_BYTES);
        payload.putShort((short) code);
        if (reason != null && !reason.isEmpty()) {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
            // stops at the last whole character that fits
            encoder.encode(CharBuffer.wrap(reason), payload, true);
        }
        payload.flip();
        return payload;
    }

    /**
     * Whether a close code may be sent in a close frame, or received in one (RFC 6455, 7.4).
     *
     * @param code The close code
     * @return Whether it is valid on the wire
     */
    public static boolean isValidCloseCode(int code) {
        return (code >= 1000 && code <= 1003)
            || (code >= 1007 && code <= 1014)
            || (code >= 3000 && code <= 4999);
    }

    /**
     * Encodes a text message.
     *
     * @param text The text
     * @return Its UTF-8 bytes
     * @throws CharacterCodingException If it holds an unpaired surrogate, which UTF-8 cannot carry
     */
    public static ByteBuffer utf8(String text) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text));
    }
}
