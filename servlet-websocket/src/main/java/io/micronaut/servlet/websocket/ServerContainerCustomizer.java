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
import jakarta.websocket.WebSocketContainer;

import java.time.Duration;

/**
 * Applies the Micronaut defaults to a container's {@code WebSocketContainer}.
 *
 * <p>Jetty, Tomcat and Undertow ship different message size and idle timeout defaults, so
 * these are always set explicitly to make behaviour the same on every runtime and equal to
 * that of the Netty server.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
public final class ServerContainerCustomizer {

    private ServerContainerCustomizer() {
    }

    /**
     * Applies the configured defaults.
     *
     * @param container     The container
     * @param configuration The WebSocket configuration
     * @param idleTimeout   The idle timeout to apply
     */
    public static void apply(WebSocketContainer container,
                             ServletWebSocketConfiguration configuration,
                             Duration idleTimeout) {
        container.setDefaultMaxTextMessageBufferSize(configuration.getMaxTextMessageSize());
        container.setDefaultMaxBinaryMessageBufferSize(configuration.getMaxBinaryMessageSize());
        container.setDefaultMaxSessionIdleTimeout(idleTimeout.toMillis());
        Duration asyncSendTimeout = configuration.getAsyncSendTimeout();
        if (asyncSendTimeout != null) {
            container.setAsyncSendTimeout(asyncSendTimeout.toMillis());
        }
    }
}
