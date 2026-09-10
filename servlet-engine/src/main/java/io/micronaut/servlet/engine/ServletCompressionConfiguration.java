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
package io.micronaut.servlet.engine;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.convert.format.ReadableBytes;
import io.micronaut.core.util.Toggleable;

import java.util.Set;

/**
 * Response compression, applied by the container.
 *
 * <p>Each container compresses responses itself, so this configuration is wired to the container's own encoder
 * rather than reimplemented as a filter: the containers already settle the cases that make compression awkward,
 * such as HEAD, ranges, already encoded bodies and the {@code Vary} header they have to add.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Experimental
@ConfigurationProperties(ServletCompressionConfiguration.PREFIX)
public class ServletCompressionConfiguration implements Toggleable {

    /**
     * The configuration prefix.
     */
    public static final String PREFIX = "micronaut.servlet.compression";

    /**
     * Whether compression is enabled by default.
     */
    public static final boolean DEFAULT_ENABLED = true;

    /**
     * The default size a response must reach before it is worth compressing, in bytes.
     */
    public static final int DEFAULT_THRESHOLD = 1024;

    /**
     * The content types compressed by default. Anything already compressed, such as an image or an archive, only
     * grows when it is compressed again.
     */
    private static final Set<String> DEFAULT_CONTENT_TYPES = Set.of(
        "text/html",
        "text/xml",
        "text/plain",
        "text/css",
        "text/javascript",
        "application/javascript",
        "application/json",
        "application/xml",
        "application/xhtml+xml",
        "image/svg+xml"
    );

    private boolean enabled = DEFAULT_ENABLED;
    private int threshold = DEFAULT_THRESHOLD;
    private Set<String> contentTypes = DEFAULT_CONTENT_TYPES;

    /**
     * @return Whether responses are compressed when the client accepts it
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled Whether responses are compressed. Default value ({@value #DEFAULT_ENABLED})
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return The size a response must reach before it is compressed, in bytes
     */
    public int getThreshold() {
        return threshold;
    }

    /**
     * @param threshold The size a response must reach before it is compressed. Default value
     *                  ({@value #DEFAULT_THRESHOLD} bytes)
     */
    public void setThreshold(@ReadableBytes int threshold) {
        this.threshold = threshold;
    }

    /**
     * @return The content types that are compressed
     */
    public Set<String> getContentTypes() {
        return contentTypes;
    }

    /**
     * @param contentTypes The content types that are compressed
     */
    public void setContentTypes(Set<String> contentTypes) {
        if (contentTypes != null && !contentTypes.isEmpty()) {
            this.contentTypes = contentTypes;
        }
    }
}
