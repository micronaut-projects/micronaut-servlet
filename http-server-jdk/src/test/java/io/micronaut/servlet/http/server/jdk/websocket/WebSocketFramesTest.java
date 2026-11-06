package io.micronaut.servlet.http.server.jdk.websocket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketFramesTest {

    @Test
    void writesUnmaskedFramesWithTheShortestLength() throws IOException {
        assertArrayEquals(new byte[] {(byte) 0x81, 0x02, 'h', 'i'}, write(true, 0x1, "hi".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(new byte[] {0x02, 0x00}, write(false, 0x2, new byte[0]));

        byte[] medium = write(true, 0x2, new byte[126]);
        assertArrayEquals(new byte[] {(byte) 0x82, 126, 0, 126}, Arrays.copyOf(medium, 4));
        assertEquals(4 + 126, medium.length);

        byte[] large = write(true, 0x2, new byte[65536]);
        assertArrayEquals(new byte[] {(byte) 0x82, 127, 0, 0, 0, 0, 0, 1, 0, 0}, Arrays.copyOf(large, 10));
        assertEquals(10 + 65536, large.length);
    }

    @Test
    void writesTheRemainingBytesOfABuffer() throws IOException {
        ByteBuffer direct = ByteBuffer.allocateDirect(3).put(new byte[] {1, 2, 3}).flip();
        direct.get();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        WebSocketFrames.write(out, true, 0x2, direct);

        assertArrayEquals(new byte[] {(byte) 0x82, 2, 2, 3}, out.toByteArray());
        assertEquals(1, direct.position());
    }

    @Test
    void theCloseReasonIsCutToAControlFrameAtACharacter() {
        String reason = "€".repeat(60);
        ByteBuffer payload = WebSocketFrames.closePayload(1000, reason);

        assertTrue(payload.remaining() <= 125);
        assertEquals(1000, payload.getShort(0));
        byte[] text = new byte[payload.remaining() - 2];
        payload.get(2, text);
        assertEquals("€".repeat(41), new String(text, StandardCharsets.UTF_8));
        assertEquals(0, WebSocketFrames.closePayload(1005, "ignored").remaining());
    }

    @Test
    void validCloseCodes() {
        for (int code : List.of(1000, 1001, 1002, 1003, 1007, 1011, 1014, 3000, 4999)) {
            assertTrue(WebSocketFrames.isValidCloseCode(code), String.valueOf(code));
        }
        for (int code : List.of(0, 999, 1004, 1005, 1006, 1015, 2000, 5000)) {
            assertFalse(WebSocketFrames.isValidCloseCode(code), String.valueOf(code));
        }
    }

    @Test
    void textThatIsNotUnicodeCannotBeSent() {
        assertThrows(CharacterCodingException.class, () -> WebSocketFrames.utf8("\ud800"));
    }

    @Test
    void theAcceptOfTheHandshake() {
        // RFC 6455, 1.3
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketHandshake.accept("dGhlIHNhbXBsZSBub25jZQ=="));
        assertTrue(WebSocketHandshake.isValidKey("dGhlIHNhbXBsZSBub25jZQ=="));
        assertFalse(WebSocketHandshake.isValidKey("c2hvcnQ="));
        assertFalse(WebSocketHandshake.isValidKey("not base64!"));
        assertFalse(WebSocketHandshake.isValidKey(null));
    }

    @Test
    void theSubprotocolsOfTheClient() {
        assertEquals(List.of("chat", "superchat", "v2"), WebSocketHandshake.subprotocols(List.of("chat, superchat", " v2 ,")));
        assertEquals(List.of(), WebSocketHandshake.subprotocols(null));
    }

    private static byte[] write(boolean fin, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketFrames.write(out, fin, opcode, ByteBuffer.wrap(payload));
        return out.toByteArray();
    }
}
