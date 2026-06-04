/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.servlet.http.server.jdk;

import com.sun.net.httpserver.HttpExchange;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.Internal;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@link ServletInputStream} implementation backed by a {@link HttpExchange#getRequestBody()}.
 */
@Internal
@Experimental
final class HttpExchangeServletInputStream extends ServletInputStream {
    private final HttpExchange httpExchange;
    private @Nullable InputStream inputStream;

    HttpExchangeServletInputStream(HttpExchange httpExchange) {
        this.httpExchange = httpExchange;
    }

    @Override
    public boolean isFinished() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean isReady() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public int read() throws IOException {
        return getInputStream().read();
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (inputStream != null) {
            inputStream.close();
        }
    }

    private InputStream getInputStream() {
        if (inputStream == null) {
            inputStream = httpExchange.getRequestBody();
        }
        return inputStream;
    }
}
