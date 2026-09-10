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
package io.micronaut.servlet.http.server.jdk;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request and response streams that bridge the JDK exchange to the Servlet API.
 */
class ServletStreamsTest {

    @Test
    void theInputStreamReadsInBulkAndTracksTheEnd() throws IOException {
        HttpExchangeServletInputStream stream = new HttpExchangeServletInputStream(exchangeWithBody("hello world"));

        assertFalse(stream.isFinished());
        assertTrue(stream.isReady());
        assertEquals(11, stream.available());

        byte[] buffer = new byte[5];
        assertEquals(5, stream.read(buffer, 0, 5));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), buffer);
        assertFalse(stream.isFinished(), "there are still bytes to come");

        assertArrayEquals(" world".getBytes(StandardCharsets.UTF_8), stream.readAllBytes());
        assertEquals(-1, stream.read(), "a drained stream reports the end");
        assertTrue(stream.isFinished());
        assertFalse(stream.isReady());

        stream.close();
    }

    @Test
    void theInputStreamReadsSingleBytes() throws IOException {
        HttpExchangeServletInputStream stream = new HttpExchangeServletInputStream(exchangeWithBody("ab"));

        assertEquals('a', stream.read());
        assertEquals('b', stream.read());
        assertEquals(-1, stream.read());
        assertTrue(stream.isFinished());
    }

    @Test
    void theInputStreamRejectsNonBlockingReads() {
        HttpExchangeServletInputStream stream = new HttpExchangeServletInputStream(exchangeWithBody(""));

        assertThrows(UnsupportedOperationException.class, () -> stream.setReadListener(null),
            "the JDK server has no non-blocking read API");
    }

    @Test
    void theOutputStreamWritesThroughToTheExchange() throws IOException {
        RecordingExchange exchange = exchangeWithBody("");
        HttpExchangeServletOutputStream stream = new HttpExchangeServletOutputStream(exchange);

        assertTrue(stream.isReady());
        stream.write('a');
        stream.write("bcdef".getBytes(StandardCharsets.UTF_8), 0, 5);
        stream.flush();
        stream.close();

        assertEquals("abcdef", exchange.responseBody.toString());
    }

    @Test
    void theOutputStreamDiscardsWritesWhenThereIsNoExchange() throws IOException {
        // a response that may not carry a body is given no exchange, so writes go nowhere instead of corrupting one
        // that was announced as body-less
        HttpExchangeServletOutputStream stream = new HttpExchangeServletOutputStream(null);

        stream.write('a');
        stream.write("bcdef".getBytes(StandardCharsets.UTF_8), 0, 5);
        stream.flush();
        stream.close();
    }

    @Test
    void theOutputStreamRejectsNonBlockingWrites() {
        HttpExchangeServletOutputStream stream = new HttpExchangeServletOutputStream(null);

        assertThrows(UnsupportedOperationException.class, () -> stream.setWriteListener(null),
            "the JDK server has no non-blocking write API");
    }

    private static RecordingExchange exchangeWithBody(String body) {
        return new RecordingExchange(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Minimal {@link HttpExchange} exposing a fixed request body and capturing the response body.
     */
    private static final class RecordingExchange extends HttpExchange {
        private final InputStream requestBody;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final Headers headers = new Headers();

        private RecordingExchange(byte[] body) {
            this.requestBody = new ByteArrayInputStream(body);
        }

        @Override
        public Headers getRequestHeaders() {
            return headers;
        }

        @Override
        public Headers getResponseHeaders() {
            return headers;
        }

        @Override
        public URI getRequestURI() {
            return URI.create("/");
        }

        @Override
        public String getRequestMethod() {
            return "GET";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
            // nothing to release
        }

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            // headers are not the subject of this test
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress(0);
        }

        @Override
        public int getResponseCode() {
            return -1;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress(0);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
            // attributes are not used by the streams
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
            // streams are fixed for this fake
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
