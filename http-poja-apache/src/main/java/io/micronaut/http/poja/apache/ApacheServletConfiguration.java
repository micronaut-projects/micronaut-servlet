/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.http.poja.apache;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Creator;
import io.micronaut.core.bind.annotation.Bindable;

import java.time.Duration;

/**
 * Configuration specific to the Apache POJA serverless application.
 *
 * @param inputBufferSize The size of the buffer that is used to read and parse the HTTP request
 *                        (in bytes). Default value is 8192 (8Kb).
 * @param outputBufferSize The size of the buffer that is used to write the HTTP response
 *                        (in bytes). Default value is 8192 (8Kb).
 * @param useInheritedChannel When true, the inherited channel will be used by if present.
 *                            Otherwise, STDIN and STDOUT will be used.
 * @param maxLineLength The maximum length of the request line, of a header line and of a chunk-size line
 *                      (in bytes). A request with a longer line is rejected with {@code 400 Bad Request}.
 *                      Default value is 8192 (8Kb).
 * @param maxHeaderCount The maximum number of request headers, and of trailers. Default value is 100.
 * @param discardTimeout How long the runtime waits for the rest of a request body the application left unread,
 *                       after the response has been sent, before it closes the connection instead. Default value is
 *                       10 seconds. It can only be enforced on a socket or channel: when reading standard input,
 *                       a body the application left unread closes the connection instead.
 * @author Andriy Dmytruk
 * @since 4.10.0
 */
@ConfigurationProperties("poja.apache")
public record ApacheServletConfiguration(
    @Bindable(defaultValue = "8192")
    int inputBufferSize,
    @Bindable(defaultValue = "8192")
    int outputBufferSize,
    @Bindable(defaultValue = "true")
    boolean useInheritedChannel,
    @Bindable(defaultValue = "8192")
    int maxLineLength,
    @Bindable(defaultValue = "100")
    int maxHeaderCount,
    @Bindable(defaultValue = "10s")
    Duration discardTimeout
) {

    /**
     * The default maximum line length.
     */
    public static final int DEFAULT_MAX_LINE_LENGTH = 8192;

    /**
     * The default maximum number of headers.
     */
    public static final int DEFAULT_MAX_HEADER_COUNT = 100;

    /**
     * The default time to wait for an unread request body.
     */
    public static final Duration DEFAULT_DISCARD_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Canonical constructor.
     *
     * @param inputBufferSize The input buffer size
     * @param outputBufferSize The output buffer size
     * @param useInheritedChannel Whether to use the inherited channel
     * @param maxLineLength The maximum line length
     * @param maxHeaderCount The maximum number of headers
     * @param discardTimeout How long to wait for an unread request body
     */
    @Creator
    public ApacheServletConfiguration {
    }

    /**
     * Constructor that applies the default line and header limits and discard timeout.
     *
     * @param inputBufferSize The input buffer size
     * @param outputBufferSize The output buffer size
     * @param useInheritedChannel Whether to use the inherited channel
     */
    public ApacheServletConfiguration(int inputBufferSize, int outputBufferSize, boolean useInheritedChannel) {
        this(inputBufferSize, outputBufferSize, useInheritedChannel, DEFAULT_MAX_LINE_LENGTH, DEFAULT_MAX_HEADER_COUNT,
            DEFAULT_DISCARD_TIMEOUT);
    }

}
