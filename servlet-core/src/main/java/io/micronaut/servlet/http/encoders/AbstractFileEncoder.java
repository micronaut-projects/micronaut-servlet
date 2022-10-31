/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.servlet.http.encoders;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.types.files.FileCustomizableResponseType;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.ServletHttpResponse;
import io.micronaut.servlet.http.ServletResponseEncoder;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Abstract file encoder implementation.
 * @param <T> The type
 *
 * @author graemerocher
 * @since 1.0.0
 */
public abstract class AbstractFileEncoder<T extends FileCustomizableResponseType> implements ServletResponseEncoder<T> {
    /**
     * @param response The Http response
     * @return The response
     */
    protected MutableHttpResponse<?> setDateHeader(MutableHttpResponse<?> response) {
        MutableHttpHeaders headers = response.getHeaders();
        LocalDateTime now = LocalDateTime.now();
        headers.date(now);
        return response;
    }

    /**
     * @param response     The Http response
     * @param lastModified The last modified
     */
    protected void setDateAndCacheHeaders(MutableHttpResponse response, long lastModified) {
        // Date header
        MutableHttpHeaders headers = response.getHeaders();
        LocalDateTime now = LocalDateTime.now();
        headers.date(now);

        // Add cache headers
        // TODO: Make configurable
        LocalDateTime cacheSeconds = now.plus(60, ChronoUnit.SECONDS);
        if (response.header(HttpHeaders.EXPIRES) == null) {
            headers.expires(cacheSeconds);
        }

        if (response.header(HttpHeaders.CACHE_CONTROL) == null) {
            String header = "private" + ", max-age=" + 60;
            response.header(HttpHeaders.CACHE_CONTROL, header);
        }

        if (response.header(HttpHeaders.LAST_MODIFIED) == null) {
            headers.lastModified(lastModified);
        }
    }

    /**
     * Performs if not modified handling.
     * @param value The value
     * @param request The request
     * @param response The response
     * @return True if a not modified response should be returned
     */
    protected boolean ifNotModified(@NonNull T value,
                                    ServletHttpRequest<?, ? super Object> request,
                                    ServletHttpResponse<?, ?> response) {
        long lastModified = value.getLastModified();

        // Cache Validation
        final HttpHeaders headers = request.getHeaders();
        final MediaType mediaType = value.getMediaType();
        if (mediaType != null && !response.getContentType().isPresent()) {
            response.header(HttpHeaders.CONTENT_TYPE, mediaType);
        }
        ZonedDateTime ifModifiedSince = headers.getDate(HttpHeaders.IF_MODIFIED_SINCE);
        if (ifModifiedSince != null) {

            // Only compare up to the second because the datetime format we send to the client
            // does not have milliseconds
            long ifModifiedSinceDateSeconds = ifModifiedSince.toEpochSecond();
            long fileLastModifiedSeconds = lastModified / 1000;
            if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
                return true;
            }
        }

        if (!response.getHeaders().contains(HttpHeaders.CONTENT_TYPE)) {
            response.header(HttpHeaders.CONTENT_TYPE, value.getMediaType().toString());
        }
        setDateAndCacheHeaders(response, lastModified);
        long length = value.getLength();

        if (length > -1) {
            response.header(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(length));
        } else {
            response.header(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }
        value.process(response);
        return false;
    }
}
