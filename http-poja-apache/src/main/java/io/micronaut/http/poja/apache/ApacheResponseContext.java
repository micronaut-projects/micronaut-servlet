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
package io.micronaut.http.poja.apache;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.server.exceptions.HttpServerException;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.impl.io.ChunkedOutputStream;
import org.apache.hc.core5.http.impl.io.ContentLengthOutputStream;
import org.apache.hc.core5.http.impl.io.DefaultHttpResponseWriter;
import org.apache.hc.core5.http.impl.io.IdentityOutputStream;
import org.apache.hc.core5.http.impl.io.SessionOutputBufferImpl;
import org.apache.hc.core5.http.io.SessionOutputBuffer;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

final class ApacheResponseContext implements Closeable {
    boolean connectionClose = false;
    // the request is HTTP/1.0, so a body without a length is delimited by closing the connection, never chunked
    boolean closeDelimited = false;
    ApacheServletHttpResponse<?> primaryResponse;

    private final SessionOutputBuffer outputBuffer;
    private final OutputStream out;
    private OutputStream bodyStream;
    private boolean aborted;

    ApacheResponseContext(ApacheServletConfiguration configuration, OutputStream out) {
        this.out = out;
        outputBuffer = new SessionOutputBufferImpl(configuration.outputBufferSize());
    }

    synchronized boolean isCommitted() {
        return bodyStream != null;
    }

    /**
     * Send the interim {@code 100 Continue} response a client sending {@code Expect: 100-continue} waits for
     * before it sends the body, unless the final response has already been committed.
     *
     * @return Whether the interim response was sent
     * @throws IOException If the response could not be written
     */
    synchronized boolean sendContinue() throws IOException {
        if (isCommitted()) {
            return false;
        }
        try {
            new DefaultHttpResponseWriter().write(new BasicClassicHttpResponse(HttpStatus.CONTINUE.getCode(), "Continue"), outputBuffer, out);
        } catch (HttpException e) {
            throw new HttpServerException("Could not write the 100 Continue response", e);
        }
        outputBuffer.flush(out);
        return true;
    }

    synchronized OutputStream commit(ClassicHttpResponse headers) throws IOException {
        if (isCommitted()) {
            throw new IllegalStateException("Response has already been committed");
        }

        headers.removeHeaders(HttpHeaders.TRANSFER_ENCODING);
        Header contentLengthStr = headers.getFirstHeader(HttpHeaders.CONTENT_LENGTH);
        long contentLength = contentLengthStr == null ? -1 : Long.parseLong(contentLengthStr.getValue());
        if (contentLength < 0) {
            headers.removeHeaders(HttpHeaders.CONTENT_LENGTH);
            if (closeDelimited) {
                // RFC 9112 section 6.1: no Transfer-Encoding in a response to an HTTP/1.0 request
                connectionClose = true;
            } else {
                headers.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
            }
        }

        Header connection = headers.getFirstHeader(HttpHeaders.CONNECTION);
        if (connection != null && connection.getValue().equalsIgnoreCase("close")) {
            connectionClose = true;
        }

        DefaultHttpResponseWriter responseWriter = new DefaultHttpResponseWriter();
        try {
            responseWriter.write(headers, outputBuffer, out);
        } catch (HttpException e) {
            throw new HttpServerException("Could not write response headers", e);
        }

        OutputStream s;
        if (contentLength >= 0) {
            s = new ContentLengthOutputStream(outputBuffer, out, contentLength);
        } else if (closeDelimited) {
            s = new IdentityOutputStream(outputBuffer, out);
        } else {
            s = new ChunkedOutputStream(outputBuffer, out, 0);
        }
        bodyStream = s;
        return s;
    }

    /**
     * Complete the response body and flush it, leaving the connection open.
     *
     * @throws IOException If the response could not be written
     */
    void finish() throws IOException {
        if (bodyStream != null) {
            bodyStream.close();
        }
        outputBuffer.flush(out);
    }

    /**
     * Give up on the response: the connection is closed without completing it, so that a client sees a response
     * that failed part way as truncated rather than as complete.
     */
    void abort() {
        aborted = true;
        connectionClose = true;
    }

    private void flushQuietly() {
        // what was written so far is sent, without the end of the body
        try {
            if (bodyStream != null) {
                bodyStream.flush();
            }
            outputBuffer.flush(out);
        } catch (IOException e) {
            // the connection is gone already
        }
    }

    @Override
    public void close() throws IOException {
        if (aborted) {
            flushQuietly();
        } else {
            finish();
        }
        if (connectionClose) {
            try {
                out.close();
            } catch (IOException e) {
                // the connection is gone already
            }
        }
    }
}
