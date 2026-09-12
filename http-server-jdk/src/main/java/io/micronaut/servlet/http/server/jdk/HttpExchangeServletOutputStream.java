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
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;

/**
 * {@link ServletOutputStream} backed by {@link HttpExchange#getResponseBody()}.
 *
 * <p>Constructed with a {@code null} exchange when the response may not carry a body (1xx, 204, 304, or a response to
 * HEAD), in which case writes are discarded rather than corrupting an exchange that was announced as body-less.</p>
 */
@Internal
@Experimental
final class HttpExchangeServletOutputStream extends ServletOutputStream {

    private final @Nullable HttpExchange httpExchange;
    private @Nullable OutputStream responseBody;

    HttpExchangeServletOutputStream(@Nullable HttpExchange httpExchange) {
        this.httpExchange = httpExchange;
    }

    @Override
    public boolean isReady() {
        // this stream is always blocking; the JDK HTTP server has no non-blocking write API
        return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        throw new UnsupportedOperationException("The JDK HTTP server does not support non-blocking writes");
    }

    @Override
    public void write(int b) throws IOException {
        OutputStream out = responseBody();
        if (out != null) {
            out.write(b);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        OutputStream out = responseBody();
        if (out != null) {
            out.write(b, off, len);
        }
    }

    @Override
    public void flush() throws IOException {
        super.flush();
        OutputStream out = responseBody();
        if (out != null) {
            out.flush();
        }
    }

    @Override
    public void close() throws IOException {
        super.close();
        OutputStream out = responseBody();
        if (out != null) {
            out.close();
        }
    }

    private @Nullable OutputStream responseBody() {
        if (responseBody == null && httpExchange != null) {
            responseBody = httpExchange.getResponseBody();
        }
        return responseBody;
    }
}
