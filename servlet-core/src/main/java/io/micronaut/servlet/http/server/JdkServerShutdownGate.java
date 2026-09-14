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
package io.micronaut.servlet.http.server;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns new requests away with {@code 503 Service Unavailable} once a graceful shutdown has begun.
 *
 * <p>{@link com.sun.net.httpserver.HttpServer} has no way to pause accepting, and stopping it cuts off the
 * requests in flight, so under steady traffic the server could never become idle within the grace period. This
 * filter is the first on every context: after {@link #close()} it answers each request before the handler is
 * reached and asks the client to close the connection, so only the requests already being handled remain to be
 * waited for, as the servlet containers arrange with their own connector pause or shutdown handler.</p>
 *
 * @since 6.2.0
 */
@Internal
@Singleton
final class JdkServerShutdownGate extends Filter {

    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        if (closed.get()) {
            exchange.getResponseHeaders().set(HttpHeaders.CONNECTION, "close");
            exchange.sendResponseHeaders(HttpStatus.SERVICE_UNAVAILABLE.getCode(), -1);
            exchange.close();
            return;
        }
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Micronaut graceful shutdown gate";
    }

    /**
     * Stops new requests from reaching the handlers.
     */
    void close() {
        closed.set(true);
    }

    /**
     * @return Whether the gate has been closed
     */
    boolean isClosed() {
        return closed.get();
    }
}
