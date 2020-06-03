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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal class that represents an {@link ServletHttpRequest} that includes the body already decoded.
 *
 * @param <N> The native request type
 * @param <B> The body type
 * @author graemerocher
 * @since 2.0.0
 */
@Internal
final class ServletRequestAndBody<N, B> extends HttpRequestWrapper<B> implements ServletHttpRequest<N, B> {

    private final Argument<B> bodyType;

    /**
     * @param delegate The Http Request
     * @param bodyType     The body, never null
     */
    ServletRequestAndBody(ServletHttpRequest<N, B> delegate, Argument<B> bodyType) {
        super(delegate);
        this.bodyType = Objects.requireNonNull(bodyType, "Body type cannot be null");
    }

    @Override
    public boolean isAsyncSupported() {
        return ((ServletHttpRequest<N, B>) getDelegate()).isAsyncSupported();
    }

    @Override
    public Optional<B> getBody() {
        return getBody(bodyType);
    }

    @Override
    public String getContextPath() {
        return ((ServletHttpRequest<N, B>) getDelegate()).getContextPath();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return ((ServletHttpRequest<N, B>) getDelegate()).getInputStream();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return ((ServletHttpRequest<N, B>) getDelegate()).getReader();
    }

    @Override
    public N getNativeRequest() {
        return ((ServletHttpRequest<N, B>) getDelegate()).getNativeRequest();
    }
}
