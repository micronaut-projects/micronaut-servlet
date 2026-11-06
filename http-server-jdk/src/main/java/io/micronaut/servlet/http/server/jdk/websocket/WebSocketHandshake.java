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
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The opening handshake of RFC 6455, 4.2.
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
public final class WebSocketHandshake {

    /**
     * The one version of the protocol there is.
     */
    public static final String VERSION = "13";

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int KEY_LENGTH = 16;

    private WebSocketHandshake() {
    }

    /**
     * Whether a {@code Sec-WebSocket-Key} is the base64 of 16 bytes, as the client must send.
     *
     * @param key The header value
     * @return Whether it is valid
     */
    public static boolean isValidKey(@Nullable String key) {
        if (key == null) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(key.trim()).length == KEY_LENGTH;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The {@code Sec-WebSocket-Accept} answering a key.
     *
     * @param key The {@code Sec-WebSocket-Key} of the client
     * @return The accept value
     */
    @SuppressWarnings("java:S4790") // SHA-1 is what RFC 6455 prescribes, not a security choice
    public static String accept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key.trim() + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }

    /**
     * The subprotocols a client asks for, in its order of preference.
     *
     * @param headerValues The values of its {@code Sec-WebSocket-Protocol} headers
     * @return The subprotocols
     */
    public static List<String> subprotocols(@Nullable List<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty()) {
            return List.of();
        }
        List<String> protocols = new ArrayList<>();
        for (String value : headerValues) {
            for (String protocol : value.split(",")) {
                String trimmed = protocol.trim();
                if (!trimmed.isEmpty()) {
                    protocols.add(trimmed);
                }
            }
        }
        return protocols;
    }
}
