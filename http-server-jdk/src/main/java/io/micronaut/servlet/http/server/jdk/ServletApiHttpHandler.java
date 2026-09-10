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
import com.sun.net.httpserver.HttpHandler;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.http.form.FormUrlEncodedDecoder;
import io.micronaut.servlet.http.ServletHttpHandler;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Implementation of {@link HttpHandler} powered by {ServletHttpHandler}.
 */
@Experimental
@Requires(missingBeans = HttpHandler.class)
@Singleton
final class ServletApiHttpHandler implements HttpHandler {
    /**
     * Passed to {@link HttpExchange#sendResponseHeaders(int, long)} when the response carries no body at all.
     */
    private static final long NO_BODY = -1L;

    /**
     * Passed to {@link HttpExchange#sendResponseHeaders(int, long)} when the body length is not known up front,
     * which makes the JDK server use chunked encoding.
     */
    private static final long CHUNKED = 0L;

    private final ServletHttpHandler<HttpServletRequest, HttpServletResponse> httpHandler;
    private final FormUrlEncodedDecoder formUrlEncodedDecoder;

    ServletApiHttpHandler(ServletHttpHandler<HttpServletRequest,
        HttpServletResponse> httpHandler,
                          FormUrlEncodedDecoder formUrlEncodedDecoder) {
        this.httpHandler = httpHandler;
        this.formUrlEncodedDecoder = formUrlEncodedDecoder;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        // all per-request state lives on the request and response objects; this handler is a singleton
        HttpExchangeHttpServletResponse response = new HttpExchangeHttpServletResponse(
            httpExchange,
            rsp -> populateAndSendResponseHeaders(rsp, httpExchange)
        );
        HttpServletRequest request = new HttpExchangeHttpServletRequest(httpExchange, formUrlEncodedDecoder);
        try {
            httpHandler.exchange(request, response);
            // nothing requested the output stream, so the response has no body and the headers are still unsent
            response.commitHeaders();
        } finally {
            response.setCommitted(true);
            httpExchange.close();
        }
    }

    private void populateAndSendResponseHeaders(HttpExchangeHttpServletResponse response,
                                                HttpExchange exchange) throws IOException {
        for (String headerName : response.getHeaderNames()) {
            exchange.getResponseHeaders().put(headerName, new ArrayList<>(response.getHeaders(headerName)));
        }
        exchange.sendResponseHeaders(response.getStatus(), responseLength(response));
    }

    /**
     * Resolves the {@code responseLength} argument of {@link HttpExchange#sendResponseHeaders(int, long)}.
     *
     * <p>The JDK server reads {@code 0} as "chunked, the caller will write an arbitrary number of bytes and close the
     * exchange". Passing it for a response that never writes a body leaves the exchange half finished and the
     * connection hangs, which is why a body-less response has to be announced with {@code -1} instead. See
     * <a href="https://github.com/micronaut-projects/micronaut-servlet/issues/1117">#1117</a>.</p>
     *
     * @param response The response
     * @return {@code -1} for no body, {@code 0} for a chunked body of unknown length, otherwise the fixed body length
     */
    private static long responseLength(HttpExchangeHttpServletResponse response) {
        if (!response.isBodyAllowed() || !response.isOutputStreamRequested()) {
            return NO_BODY;
        }
        long declaredLength = response.getDeclaredContentLength();
        if (declaredLength == 0L) {
            return NO_BODY;
        }
        return declaredLength > 0L ? declaredLength : CHUNKED;
    }
}
