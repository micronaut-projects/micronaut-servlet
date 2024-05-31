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
package io.micronaut.servlet.engine;

import io.micronaut.core.annotation.Internal;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;

/**
 * Delegating {@link InputStream} that only calls {@link HttpServletRequest#getInputStream()} on
 * first read.
 *
 * @since 4.9.0
 * @author Jonas Konrad
 */
@Internal
final class LazyDelegateInputStream extends InputStream {
    private HttpServletRequest request;
    private InputStream delegate;

    LazyDelegateInputStream(HttpServletRequest request) {
        this.request = request;
    }

    private InputStream delegate() throws IOException {
        if (delegate == null) {
            delegate = request.getInputStream();
            request = null;
        }
        return delegate;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate().read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        return delegate().skip(n);
    }

    @Override
    public int read() throws IOException {
        return delegate().read();
    }

    @Override
    public int available() throws IOException {
        return delegate().available();
    }

    @Override
    public void close() throws IOException {
        delegate().close();
    }
}
