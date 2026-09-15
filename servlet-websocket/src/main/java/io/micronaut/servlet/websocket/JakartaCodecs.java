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
package io.micronaut.servlet.websocket;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The decoders and encoders of one Jakarta endpoint instance, with the lifecycle the
 * specification gives them: created and initialized when the connection opens, destroyed when it
 * closes.
 *
 * <p>Decoding follows Jakarta WebSocket 4.7: the decoders are tried in the order declared, the
 * first whose {@code willDecode} accepts the message decodes it, and the result has to be of the
 * handler's parameter type. Encoding applies to the value an {@code @OnMessage} method returns,
 * choosing the encoder whose type argument the value is an instance of.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
final class JakartaCodecs {

    private static final Logger LOG = LoggerFactory.getLogger(JakartaCodecs.class);

    private final List<Decoder> decoders;
    private final List<TypedEncoder> encoders;

    private JakartaCodecs(List<Decoder> decoders, List<TypedEncoder> encoders) {
        this.decoders = decoders;
        this.encoders = encoders;
    }

    /**
     * Creates and initializes the codecs an endpoint declares.
     *
     * @param endpoint   The endpoint
     * @param components The resolver for the declared classes
     * @param config     The configuration the codecs are initialized with
     * @return The codecs
     */
    static JakartaCodecs create(JakartaEndpoint endpoint, JakartaEndpointComponents components, EndpointConfig config) {
        List<Decoder> decoders = new ArrayList<>(endpoint.decoders().size());
        for (Class<?> type : endpoint.decoders()) {
            Decoder decoder = (Decoder) components.instantiate(type);
            decoder.init(config);
            decoders.add(decoder);
        }
        List<TypedEncoder> encoders = new ArrayList<>(endpoint.encoders().size());
        for (Class<?> type : endpoint.encoders()) {
            Encoder encoder = (Encoder) components.instantiate(type);
            encoder.init(config);
            encoders.add(new TypedEncoder(encoder, components.encodedType(type)));
        }
        return new JakartaCodecs(decoders, encoders);
    }

    /**
     * Whether any codec is declared at all, so an endpoint without them pays nothing.
     *
     * @return {@code true} if there is at least one decoder or encoder
     */
    boolean isEmpty() {
        return decoders.isEmpty() && encoders.isEmpty();
    }

    /**
     * Decodes a text message for the given parameter.
     *
     * @param text     The message
     * @param argument The handler's message parameter
     * @return The decoded message, or {@code null} if no declared decoder produces the parameter type
     * @throws DecodeException if a decoder fails
     * @throws IOException     if a stream decoder cannot read the message
     */
    @Nullable Object decodeText(String text, Argument<?> argument) throws DecodeException, IOException {
        Class<?> type = argument.getType();
        for (Decoder decoder : decoders) {
            Object decoded = null;
            if (decoder instanceof Decoder.Text<?> textDecoder) {
                if (textDecoder.willDecode(text)) {
                    decoded = textDecoder.decode(text);
                }
            } else if (decoder instanceof Decoder.TextStream<?> streamDecoder) {
                decoded = streamDecoder.decode(new StringReader(text));
            }
            if (type.isInstance(decoded)) {
                return decoded;
            }
        }
        return null;
    }

    /**
     * Decodes a binary message for the given parameter.
     *
     * @param bytes    The message
     * @param argument The handler's message parameter
     * @return The decoded message, or {@code null} if no declared decoder produces the parameter type
     * @throws DecodeException if a decoder fails
     * @throws IOException     if a stream decoder cannot read the message
     */
    @Nullable Object decodeBinary(byte[] bytes, Argument<?> argument) throws DecodeException, IOException {
        Class<?> type = argument.getType();
        for (Decoder decoder : decoders) {
            Object decoded = null;
            if (decoder instanceof Decoder.Binary<?> binaryDecoder) {
                if (binaryDecoder.willDecode(ByteBuffer.wrap(bytes))) {
                    decoded = binaryDecoder.decode(ByteBuffer.wrap(bytes));
                }
            } else if (decoder instanceof Decoder.BinaryStream<?> streamDecoder) {
                decoded = streamDecoder.decode(new ByteArrayInputStream(bytes));
            }
            if (type.isInstance(decoded)) {
                return decoded;
            }
        }
        return null;
    }

    /**
     * Encodes a value with the first declared encoder that accepts its type.
     *
     * @param value The value
     * @return The encoded text or binary message, or the value itself when no encoder accepts it
     * @throws EncodeException if the encoder fails
     * @throws IOException     if a stream encoder cannot write the message
     */
    Object encode(Object value) throws EncodeException, IOException {
        for (TypedEncoder candidate : encoders) {
            if (candidate.type != null && !candidate.type.isInstance(value)) {
                continue;
            }
            try {
                return encode(candidate.encoder, value);
            } catch (ClassCastException e) {
                if (candidate.type != null) {
                    throw e;
                }
                // The encoder's type is unknown and this is not a value it takes; try the next one.
            }
        }
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object encode(Encoder encoder, Object value) throws EncodeException, IOException {
        if (encoder instanceof Encoder.Text textEncoder) {
            return textEncoder.encode(value);
        }
        if (encoder instanceof Encoder.Binary binaryEncoder) {
            return binaryEncoder.encode(value);
        }
        if (encoder instanceof Encoder.TextStream textStreamEncoder) {
            StringWriter writer = new StringWriter();
            textStreamEncoder.encode(value, writer);
            return writer.toString();
        }
        if (encoder instanceof Encoder.BinaryStream binaryStreamEncoder) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            binaryStreamEncoder.encode(value, out);
            return ByteBuffer.wrap(out.toByteArray());
        }
        return value;
    }

    /**
     * Destroys every codec; the connection is closed.
     */
    void destroy() {
        for (Decoder decoder : decoders) {
            destroyQuietly(decoder::destroy, decoder);
        }
        for (TypedEncoder encoder : encoders) {
            destroyQuietly(encoder.encoder::destroy, encoder.encoder);
        }
    }

    private static void destroyQuietly(Runnable destroy, Object codec) {
        try {
            destroy.run();
        } catch (Exception e) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Error destroying WebSocket codec [{}]: {}", codec.getClass().getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * An encoder with the type it encodes.
     *
     * @param encoder The encoder
     * @param type    The type it encodes, {@code null} when it could not be resolved and the
     *                encoder is then offered every value
     */
    private record TypedEncoder(Encoder encoder, @Nullable Class<?> type) {
    }
}
