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

import io.micronaut.context.annotation.ConfigurationProperties;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Configuration for WebSocket support on servlet containers.
 *
 * <p>Message sizes and the idle timeout are always applied explicitly, because the three
 * supported containers ship different defaults. The values here match those of the Netty
 * server so that behaviour is the same on every runtime.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@ConfigurationProperties(ServletWebSocketConfiguration.PREFIX)
public class ServletWebSocketConfiguration {

    /**
     * The configuration prefix.
     */
    public static final String PREFIX = "micronaut.servlet.websocket";

    /**
     * The property used to enable or disable WebSocket support.
     */
    public static final String ENABLED_PROPERTY = PREFIX + ".enabled";

    /**
     * The default maximum message size in bytes.
     */
    public static final int DEFAULT_MAX_MESSAGE_SIZE = 65536;

    /**
     * The default number of sends that may be queued before a session reports that it is
     * no longer writable.
     */
    public static final int DEFAULT_MAX_PENDING_SENDS = 64;

    private boolean enabled = true;
    private int maxTextMessageSize = DEFAULT_MAX_MESSAGE_SIZE;
    private int maxBinaryMessageSize = DEFAULT_MAX_MESSAGE_SIZE;
    private @Nullable Duration idleTimeout;
    private @Nullable Duration asyncSendTimeout;
    private int maxPendingSends = DEFAULT_MAX_PENDING_SENDS;
    private CompressionConfiguration compression = new CompressionConfiguration();

    /**
     * @return Whether WebSocket support is enabled. Defaults to {@code true}.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled Whether WebSocket support is enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return The maximum size in bytes of an inbound text message. Default value ({@value #DEFAULT_MAX_MESSAGE_SIZE}).
     */
    public int getMaxTextMessageSize() {
        return maxTextMessageSize;
    }

    /**
     * @param maxTextMessageSize The maximum size in bytes of an inbound text message
     */
    public void setMaxTextMessageSize(int maxTextMessageSize) {
        this.maxTextMessageSize = maxTextMessageSize;
    }

    /**
     * @return The maximum size in bytes of an inbound binary message. Default value ({@value #DEFAULT_MAX_MESSAGE_SIZE}).
     */
    public int getMaxBinaryMessageSize() {
        return maxBinaryMessageSize;
    }

    /**
     * @param maxBinaryMessageSize The maximum size in bytes of an inbound binary message
     */
    public void setMaxBinaryMessageSize(int maxBinaryMessageSize) {
        this.maxBinaryMessageSize = maxBinaryMessageSize;
    }

    /**
     * @return How long a session may stay idle before it is closed, or {@code null} to
     * inherit {@code micronaut.server.idle-timeout}
     */
    public @Nullable Duration getIdleTimeout() {
        return idleTimeout;
    }

    /**
     * @param idleTimeout How long a session may stay idle before it is closed
     */
    public void setIdleTimeout(@Nullable Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    /**
     * @return How long an asynchronous send may take before it fails, or {@code null} to
     * use the container default
     */
    public @Nullable Duration getAsyncSendTimeout() {
        return asyncSendTimeout;
    }

    /**
     * @param asyncSendTimeout How long an asynchronous send may take before it fails
     */
    public void setAsyncSendTimeout(@Nullable Duration asyncSendTimeout) {
        this.asyncSendTimeout = asyncSendTimeout;
    }

    /**
     * @return The number of queued sends beyond which a session reports that it is not
     * writable. Default value ({@value #DEFAULT_MAX_PENDING_SENDS}).
     */
    public int getMaxPendingSends() {
        return maxPendingSends;
    }

    /**
     * @param maxPendingSends The number of queued sends beyond which a session reports that it is not writable
     */
    public void setMaxPendingSends(int maxPendingSends) {
        this.maxPendingSends = maxPendingSends;
    }

    /**
     * @return The per-message compression configuration
     */
    public CompressionConfiguration getCompression() {
        return compression;
    }

    /**
     * @param compression The per-message compression configuration
     */
    public void setCompression(CompressionConfiguration compression) {
        this.compression = compression != null ? compression : new CompressionConfiguration();
    }

    /**
     * Configuration for the {@code permessage-deflate} extension.
     */
    @ConfigurationProperties("compression")
    public static class CompressionConfiguration {

        private boolean enabled = true;

        /**
         * @return Whether {@code permessage-deflate} may be negotiated. Defaults to {@code true}.
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * @param enabled Whether {@code permessage-deflate} may be negotiated
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
