package io.micronaut.servlet.http.server.jdk.websocket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketFrameDecoderTest {

    private static final byte[] MASK = {0x37, (byte) 0xfa, 0x21, 0x3d};

    @Test
    void unmasksATextFrame() throws IOException {
        // RFC 6455, 5.7: a single-frame masked text message
        byte[] frame = {(byte) 0x81, (byte) 0x85, 0x37, (byte) 0xfa, 0x21, 0x3d, 0x7f, (byte) 0x9f, 0x4d, 0x51, 0x58};
        Recorder recorder = new Recorder();

        assertTrue(decoder(frame).readFrame(recorder));

        assertEquals(List.of("text:Hello"), recorder.events);
    }

    @Test
    void putsFragmentsTogether() throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.writeBytes(frame(false, 0x1, "Hel".getBytes(StandardCharsets.UTF_8)));
        // a control frame may come between the fragments of a message
        frames.writeBytes(frame(true, 0x9, "p".getBytes(StandardCharsets.UTF_8)));
        frames.writeBytes(frame(false, 0x0, "l".getBytes(StandardCharsets.UTF_8)));
        frames.writeBytes(frame(true, 0x0, "o".getBytes(StandardCharsets.UTF_8)));
        WebSocketFrameDecoder decoder = decoder(frames.toByteArray());
        Recorder recorder = new Recorder();

        for (int i = 0; i < 4; i++) {
            assertTrue(decoder.readFrame(recorder));
        }

        assertEquals(List.of("ping:p", "text:Hello"), recorder.events);
    }

    @Test
    void checksTheUtf8OfAWholeMessageNotOfAFragment() throws IOException {
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        // the character is split between the fragments
        frames.writeBytes(frame(false, 0x1, Arrays.copyOfRange(euro, 0, 1)));
        frames.writeBytes(frame(true, 0x0, Arrays.copyOfRange(euro, 1, 3)));
        WebSocketFrameDecoder decoder = decoder(frames.toByteArray());
        Recorder recorder = new Recorder();

        decoder.readFrame(recorder);
        decoder.readFrame(recorder);

        assertEquals(List.of("text:€"), recorder.events);
    }

    @Test
    void readsBinaryAndPongFrames() throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.writeBytes(frame(true, 0x2, new byte[] {1, 2, 3}));
        frames.writeBytes(frame(true, 0xA, "data".getBytes(StandardCharsets.UTF_8)));
        WebSocketFrameDecoder decoder = decoder(frames.toByteArray());
        Recorder recorder = new Recorder();

        decoder.readFrame(recorder);
        decoder.readFrame(recorder);

        assertEquals(List.of("binary:3", "pong:data"), recorder.events);
        assertArrayEquals(new byte[] {1, 2, 3}, recorder.binary);
    }

    @Test
    void readsTheExtendedPayloadLengths() throws IOException {
        for (int length : new int[] {125, 126, 65535, 65536, 70000}) {
            byte[] payload = new byte[length];
            Arrays.fill(payload, (byte) 'a');
            Recorder recorder = new Recorder();

            decoder(frame(true, 0x2, payload)).readFrame(recorder);

            assertEquals(List.of("binary:" + length), recorder.events);
        }
    }

    @Test
    void readsACloseFrameWithItsCodeAndReason() throws IOException {
        Recorder recorder = new Recorder();

        assertFalse(decoder(frame(true, 0x8, closePayload(1000, "bye"))).readFrame(recorder));

        assertEquals(List.of("close:1000:bye"), recorder.events);
    }

    @Test
    void anEmptyCloseFrameHasNoStatus() throws IOException {
        Recorder recorder = new Recorder();

        assertFalse(decoder(frame(true, 0x8, new byte[0])).readFrame(recorder));

        assertEquals(List.of("close:1005:"), recorder.events);
    }

    @Test
    void refusesInvalidCloseFrames() {
        assertProtocolError(1002, frame(true, 0x8, new byte[] {0x03}));
        for (int code : new int[] {0, 999, 1004, 1005, 1006, 1015, 1016, 2999, 5000}) {
            assertProtocolError(1002, frame(true, 0x8, closePayload(code, "")));
        }
        byte[] invalidReason = {0x03, (byte) 0xe8, (byte) 0xc3, 0x28};
        assertProtocolError(1007, frame(true, 0x8, invalidReason));
    }

    @Test
    void acceptsTheCloseCodesOnTheWire() throws IOException {
        for (int code : new int[] {1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011, 3000, 4999}) {
            Recorder recorder = new Recorder();
            decoder(frame(true, 0x8, closePayload(code, ""))).readFrame(recorder);
            assertEquals(List.of("close:" + code + ":"), recorder.events);
        }
    }

    @Test
    void refusesAnUnmaskedFrame() {
        byte[] unmasked = {(byte) 0x81, 0x02, 'h', 'i'};
        assertProtocolError(1002, unmasked);
    }

    @Test
    void refusesReservedBitsAndOpcodes() {
        byte[] rsv = frame(true, 0x1, "x".getBytes(StandardCharsets.UTF_8));
        rsv[0] |= 0x40;
        assertProtocolError(1002, rsv);
        assertProtocolError(1002, frame(true, 0x3, new byte[0]));
        assertProtocolError(1002, frame(true, 0xB, new byte[0]));
    }

    @Test
    void refusesInvalidControlFrames() {
        assertProtocolError(1002, frame(false, 0x9, new byte[0]));
        assertProtocolError(1002, frame(true, 0x9, new byte[126]));
    }

    @Test
    void refusesFragmentsOutOfOrder() {
        assertProtocolError(1002, frame(true, 0x0, new byte[] {1}));
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.writeBytes(frame(false, 0x1, "a".getBytes(StandardCharsets.UTF_8)));
        frames.writeBytes(frame(true, 0x1, "b".getBytes(StandardCharsets.UTF_8)));
        WebSocketFrameDecoder decoder = decoder(frames.toByteArray());
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class, () -> {
            decoder.readFrame(new Recorder());
            decoder.readFrame(new Recorder());
        });
        assertEquals(1002, e.getCloseCode());
    }

    @Test
    void refusesInvalidUtf8() {
        assertProtocolError(1007, frame(true, 0x1, new byte[] {(byte) 0xc3, 0x28}));
        // an encoded surrogate is not UTF-8 either
        assertProtocolError(1007, frame(true, 0x1, new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80}));
    }

    @Test
    void refusesAMessageOverTheLimitBeforeReadingIt() {
        // only the header is there: the payload is never read
        byte[] header = Arrays.copyOf(frame(true, 0x1, new byte[11]), 6);
        WebSocketFrameDecoder decoder = new WebSocketFrameDecoder(new ByteArrayInputStream(header), () -> 10, () -> 100);
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class, () -> decoder.readFrame(new Recorder()));
        assertEquals(1009, e.getCloseCode());
    }

    @Test
    void theLimitIsOfTheWholeMessage() throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.writeBytes(frame(false, 0x2, new byte[6]));
        frames.writeBytes(frame(true, 0x0, new byte[6]));
        WebSocketFrameDecoder decoder = new WebSocketFrameDecoder(new ByteArrayInputStream(frames.toByteArray()), () -> 100, () -> 10);

        decoder.readFrame(new Recorder());
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class, () -> decoder.readFrame(new Recorder()));
        assertEquals(1009, e.getCloseCode());
    }

    @Test
    void refusesALengthWithItsMostSignificantBitSet() {
        byte[] frame = {(byte) 0x82, (byte) 0xFF, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0};
        assertProtocolError(1002, frame);
    }

    @Test
    void anEndedConnectionIsAnEndOfFile() {
        assertThrows(EOFException.class, () -> decoder(new byte[0]).readFrame(new Recorder()));
        byte[] truncated = Arrays.copyOf(frame(true, 0x1, "hello".getBytes(StandardCharsets.UTF_8)), 8);
        assertThrows(EOFException.class, () -> decoder(truncated).readFrame(new Recorder()));
    }

    private static void assertProtocolError(int closeCode, byte[] frames) {
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class, () -> decoder(frames).readFrame(new Recorder()));
        assertEquals(closeCode, e.getCloseCode());
    }

    private static WebSocketFrameDecoder decoder(byte[] bytes) {
        return new WebSocketFrameDecoder(new ByteArrayInputStream(bytes), () -> 1 << 20, () -> 1 << 20);
    }

    static byte[] closePayload(int code, String reason) {
        byte[] text = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + text.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(text, 0, payload, 2, text.length);
        return payload;
    }

    /**
     * A frame as a client sends it, masked.
     */
    static byte[] frame(boolean fin, int opcode, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0) | opcode);
        int length = payload.length;
        if (length <= 125) {
            out.write(0x80 | length);
        } else if (length <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write(length >>> 8);
            out.write(length);
        } else {
            out.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((long) length >>> shift));
            }
        }
        out.writeBytes(MASK);
        for (int i = 0; i < length; i++) {
            out.write(payload[i] ^ MASK[i & 3]);
        }
        return out.toByteArray();
    }

    private static final class Recorder implements WebSocketFrameDecoder.Listener {
        final List<String> events = new ArrayList<>();
        byte[] binary;

        @Override
        public void onText(String text) {
            events.add("text:" + text);
        }

        @Override
        public void onBinary(byte[] data) {
            binary = data;
            events.add("binary:" + data.length);
        }

        @Override
        public void onPing(byte[] data) {
            events.add("ping:" + new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public void onPong(byte[] data) {
            events.add("pong:" + new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public void onClose(int code, String reason) {
            events.add("close:" + code + ":" + reason);
        }
    }
}
