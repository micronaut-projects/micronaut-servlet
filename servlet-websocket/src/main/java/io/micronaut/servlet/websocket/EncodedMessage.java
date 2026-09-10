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
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * A message that has been encoded ready to be written to a Jakarta WebSocket remote
 * endpoint, as either a text or a binary frame.
 *
 * @param text   The text payload, or {@code null} if this is a binary message
 * @param binary The binary payload, or {@code null} if this is a text message
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
public record EncodedMessage(@Nullable String text, @Nullable ByteBuffer binary) {

    /**
     * Creates a text message.
     *
     * @param text The text
     * @return The message
     */
    public static EncodedMessage ofText(String text) {
        return new EncodedMessage(Objects.requireNonNull(text, "text"), null);
    }

    /**
     * Creates a binary message.
     *
     * @param binary The payload
     * @return The message
     */
    public static EncodedMessage ofBinary(ByteBuffer binary) {
        return new EncodedMessage(null, Objects.requireNonNull(binary, "binary"));
    }

    /**
     * @return Whether this is a text message
     */
    public boolean isText() {
        return text != null;
    }
}
