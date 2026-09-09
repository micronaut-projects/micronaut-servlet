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
    private boolean finished;

    HttpExchangeServletInputStream(HttpExchange httpExchange) {
        this.httpExchange = httpExchange;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public boolean isReady() {
        // this stream is always blocking; the JDK HTTP server has no non-blocking read API
        return !finished;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException("The JDK HTTP server does not support non-blocking reads");
    }

    @Override
    public int read() throws IOException {
        int read = getInputStream().read();
        if (read == -1) {
            finished = true;
        }
        return read;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        // ServletInputStream inherits a byte-at-a-time loop from InputStream, which makes readAllBytes() pay a
        // virtual call and a bounds check per byte of the request body
        int read = getInputStream().read(b, off, len);
        if (read == -1) {
            finished = true;
        }
        return read;
    }

    @Override
    public int available() throws IOException {
        return getInputStream().available();
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
