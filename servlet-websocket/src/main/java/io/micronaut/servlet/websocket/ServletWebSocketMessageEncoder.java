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
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.websocket.exceptions.WebSocketSessionException;
import jakarta.inject.Singleton;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes an outbound WebSocket message the way the Netty server does: byte-like payloads
 * become binary frames, {@code CharSequence} and {@code java.lang} types become text frames,
 * and anything else is written through the {@link MessageBodyHandlerRegistry} as a text frame
 * using the configured media type, JSON by default.
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
@Singleton
public final class ServletWebSocketMessageEncoder {

    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;
    private final ConversionService conversionService;

    /**
     * Default constructor.
     *
     * @param messageBodyHandlerRegistry The message body handler registry
     * @param conversionService          The conversion service
     */
    public ServletWebSocketMessageEncoder(MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                                          ConversionService conversionService) {
        this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
        this.conversionService = conversionService;
    }

    /**
     * Encodes the given message.
     *
     * @param message   The message, never {@code null}
     * @param mediaType The media type to use when the message needs a body writer
     * @return The encoded message
     * @throws WebSocketSessionException if the message cannot be encoded
     */
    @SuppressWarnings("unchecked")
    public EncodedMessage encode(Object message, MediaType mediaType) {
        if (message instanceof byte[] bytes) {
            return EncodedMessage.ofBinary(ByteBuffer.wrap(bytes));
        }
        if (message instanceof ByteBuffer nioBuffer) {
            return EncodedMessage.ofBinary(nioBuffer);
        }
        if (message instanceof io.micronaut.core.io.buffer.ByteBuffer<?> micronautBuffer) {
            return EncodedMessage.ofBinary(micronautBuffer.asNioBuffer());
        }
        if (message instanceof CharSequence charSequence) {
            return EncodedMessage.ofText(charSequence.toString());
        }
        if (ClassUtils.isJavaLangType(message.getClass())) {
            return EncodedMessage.ofText(message.toString());
        }
        Argument<Object> type = (Argument<Object>) Argument.of(message.getClass());
        MessageBodyWriter<Object> writer = messageBodyHandlerRegistry
            .findWriter(type, List.of(mediaType))
            .orElse(null);
        if (writer != null) {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            writer.writeTo(type, mediaType, message, new SimpleHttpHeaders(conversionService), encoded);
            return EncodedMessage.ofText(encoded.toString(StandardCharsets.UTF_8));
        }
        throw new WebSocketSessionException("Unable to encode WebSocket message: " + message);
    }
}
