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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;

/**
 * Implementation of {@link HttpHandler} powered by {ServletHttpHandler}.
 */
@Experimental
@Requires(missingBeans = HttpHandler.class)
@Singleton
final class ServletApiHttpHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ServletApiHttpHandler.class);
    private final ServletHttpHandler<HttpServletRequest, HttpServletResponse> httpHandler;
    private final FormUrlEncodedDecoder formUrlEncodedDecoder;
    private boolean headersSent = false;

    ServletApiHttpHandler(ServletHttpHandler<HttpServletRequest,
        HttpServletResponse> httpHandler,
                          FormUrlEncodedDecoder formUrlEncodedDecoder) {
        this.httpHandler = httpHandler;
        this.formUrlEncodedDecoder = formUrlEncodedDecoder;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        HttpExchangeHttpServletResponse response = new HttpExchangeHttpServletResponse(httpExchange, rsp -> {
            try {
                populateAndSendResponseHeaders(rsp, httpExchange);
            } catch (IOException e) {
                LOG.error(e.getMessage(), e);
            }
            headersSent = true;
        });
        HttpServletRequest request = new HttpExchangeHttpServletRequest(httpExchange, formUrlEncodedDecoder);
        httpHandler.exchange(request, response);
        if (!headersSent) {
            // if the headers have not been sent, e.g. nothing was written to the outpustream and hence no callback, then send them now
            populateAndSendResponseHeaders(response, httpExchange);
        }
        response.setCommitted(true);
        httpExchange.close();
    }

    void populateAndSendResponseHeaders(HttpServletResponse response,
                                        HttpExchange exchange) throws IOException {
        for (String headerName : response.getHeaderNames()) {
            exchange.getResponseHeaders().put(headerName, new ArrayList<>(response.getHeaders(headerName)));
        }
        int status = response.getStatus();
        // A response length of 0 tells the JDK HTTP server to use chunked encoding and expect
        // an arbitrary number of bytes to be written before close(). That's wrong for statuses
        // which must never carry a body (1xx, 204, 205, 304) or for HEAD requests, since nothing will
        // ever be written to the output stream; use -1 (no body) instead so the exchange completes.
        int contentLength = isBodyAllowed(status, exchange.getRequestMethod()) ? 0 : -1;
        exchange.sendResponseHeaders(status, contentLength);
    }

    private static boolean isBodyAllowed(int status, String method) {
        if ("HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        return status != HttpURLConnection.HTTP_NOT_MODIFIED
            && status != HttpURLConnection.HTTP_NO_CONTENT
            && status != HttpURLConnection.HTTP_RESET
            && status >= HttpURLConnection.HTTP_OK;
    }

}
