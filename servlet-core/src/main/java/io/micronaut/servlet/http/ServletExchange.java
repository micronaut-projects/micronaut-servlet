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
package io.micronaut.servlet.http;

import io.micronaut.core.annotation.NonNull;

import java.io.Closeable;

/**
 * Represents an HTTP exchange in a serverless context.
 *
 * @param <Req> The native request type
 * @param <Res> The native response type
 * @author graemerocher
 * @since 2.0.0
 */
public interface ServletExchange<Req, Res> extends Closeable {

    /**
     * @return The request object
     */
    ServletHttpRequest<Req, ? super Object> getRequest();

    /**
     * Get the current response, the last response created by {@link #createResponse()}.
     *
     * @return The response object
     */
    @NonNull
    ServletHttpResponse<Res, ?> getResponse();

    /**
     * Create a new, empty response for this exchange. Only the last response created for a request
     * is fully backed by the servlet container response, meaning it can access
     * {@link ServletHttpResponse#getOutputStream()} and similar methods.
     *
     * @return The response
     * @since 4.13.0
     */
    @NonNull
    default ServletHttpResponse<Res, ?> createResponse() {
        return getResponse();
    }

    @Override
    default void close() {
    }
}
