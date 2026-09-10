package io.micronaut.servlet.websocket;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.websocket.exceptions.WebSocketSessionException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServletWebSocketMessageEncoderTest {

    private final ServletWebSocketMessageEncoder empty =
        new ServletWebSocketMessageEncoder(MessageBodyHandlerRegistry.EMPTY, ConversionService.SHARED);

    @Test
    void byteLikePayloadsBecomeBinaryFrames() {
        assertFalse(empty.encode("bytes".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON_TYPE).isText());
        assertFalse(empty.encode(ByteBuffer.wrap(new byte[]{1, 2}), MediaType.APPLICATION_JSON_TYPE).isText());
        assertFalse(empty.encode(ByteArrayBufferFactory.INSTANCE.wrap(new byte[]{3}), MediaType.APPLICATION_JSON_TYPE).isText());
    }

    @Test
    void charSequencesAndJavaLangTypesBecomeTextFrames() {
        assertEquals("hello", empty.encode("hello", MediaType.APPLICATION_JSON_TYPE).text());
        assertEquals("hello", empty.encode(new StringBuilder("hello"), MediaType.APPLICATION_JSON_TYPE).text());
        assertEquals("42", empty.encode(42, MediaType.APPLICATION_JSON_TYPE).text());
        assertEquals("true", empty.encode(Boolean.TRUE, MediaType.APPLICATION_JSON_TYPE).text());
    }

    @Test
    void aPojoIsWrittenAsJsonInATextFrame() {
        try (ApplicationContext context = ApplicationContext.run()) {
            ServletWebSocketMessageEncoder encoder = new ServletWebSocketMessageEncoder(
                context.getBean(MessageBodyHandlerRegistry.class),
                context.getBean(ConversionService.class)
            );

            EncodedMessage encoded = encoder.encode(new Greeting("hi"), MediaType.APPLICATION_JSON_TYPE);

            assertTrue(encoded.isText());
            assertEquals("{\"text\":\"hi\"}", encoded.text());
        }
    }

    @Test
    void anUnencodableMessageIsReported() {
        WebSocketSessionException e = assertThrows(
            WebSocketSessionException.class,
            () -> empty.encode(new Greeting("hi"), MediaType.APPLICATION_JSON_TYPE)
        );
        assertTrue(e.getMessage().startsWith("Unable to encode WebSocket message"));
    }

    @Test
    void encodingDoesNotConsumeTheCallersBuffer() {
        // A broadcast encodes the same message once per session, and containers write
        // straight from the buffer they are given.
        ByteBuffer payload = ByteBuffer.wrap(new byte[]{1, 2, 3});

        EncodedMessage first = empty.encode(payload, MediaType.APPLICATION_OCTET_STREAM_TYPE);
        first.binary().position(first.binary().limit());
        EncodedMessage second = empty.encode(payload, MediaType.APPLICATION_OCTET_STREAM_TYPE);

        assertEquals(3, second.binary().remaining(), "every session must get the whole payload");
        assertEquals(3, payload.remaining(), "the caller's buffer is left untouched");
    }

    @Test
    void encodedMessagesCarryExactlyOnePayload() {
        EncodedMessage text = EncodedMessage.ofText("a");
        EncodedMessage binary = EncodedMessage.ofBinary(ByteBuffer.allocate(1));

        assertTrue(text.isText());
        assertEquals(null, text.binary());
        assertFalse(binary.isText());
        assertNotNull(binary.binary());
    }

    record Greeting(String text) {
    }
}
