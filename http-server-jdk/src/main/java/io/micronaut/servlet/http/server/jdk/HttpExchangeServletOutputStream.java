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

import java.io.IOException;

@Internal
@Experimental
final class HttpExchangeServletOutputStream extends ServletOutputStream {

    private final HttpExchange httpExchange;

    HttpExchangeServletOutputStream(HttpExchange httpExchange) {
        this.httpExchange = httpExchange;
    }

    @Override
    public boolean isReady() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (httpExchange.getResponseBody() != null) {
            httpExchange.getResponseBody().close();
        }
    }

    @Override
    public void write(int b) throws IOException {
        if (httpExchange.getResponseBody() != null) {
            httpExchange.getResponseBody().write(b);
        }
    }

    @Override
    public void flush() throws IOException {
        super.flush();
        if (httpExchange.getResponseBody() != null) {
            httpExchange.getResponseBody().flush();
        }
    }
}
