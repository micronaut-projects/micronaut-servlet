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
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.form.FormUrlEncodedDecoder;
import io.micronaut.servlet.http.ServletHttpHandler;
import jakarta.inject.Singleton;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.ArrayList;

/**
 * Implementation of {@link HttpHandler} powered by {ServletHttpHandler}.
 */
@Experimental
@Requires(missingBeans = HttpHandler.class)
@Singleton
class ServletApiHttpHandler implements HttpHandler {
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
        HttpExchangeHttpServletResponse response = new HttpExchangeHttpServletResponse();
        HttpServletRequest request = new HttpExchangeHttpServletRequest(httpExchange, formUrlEncodedDecoder);
        httpHandler.exchange(request, response);
        byte[] responseBody = response.getBody();
        int contentLength = populateAndRetrieveContentLength(response, responseBody);
        populateAndSendResponseHeaders(response, httpExchange, contentLength);
        response.setCommitted(true);
        writeBody(httpExchange, responseBody);
        httpExchange.close();
    }

    void writeBody(HttpExchange exchange, byte[] body) throws IOException {
        if (body.length > 0) {
            OutputStream outputStream = exchange.getResponseBody();
            outputStream.write(body);
            outputStream.flush();
            outputStream.close();
        }
    }

    void populateAndSendResponseHeaders(HttpServletResponse response,
                                        HttpExchange exchange,
                                        int contentLength) throws IOException {
        for (String headerName : response.getHeaderNames()) {
            exchange.getResponseHeaders().put(headerName, new ArrayList<>(response.getHeaders(headerName)));
        }
        exchange.sendResponseHeaders(response.getStatus(), contentLength);
    }

    int populateAndRetrieveContentLength(HttpServletResponse response, byte[] body) {
        String contentLengthObject = response.getHeader(HttpHeaders.CONTENT_LENGTH);
        int contentLength = 0;
        if (StringUtils.isEmpty(contentLengthObject)) {
            contentLength = body.length;
            response.setContentLength(contentLength);
        } else {
            contentLength = Integer.valueOf(contentLengthObject);
        }
        return contentLength;
    }
}
