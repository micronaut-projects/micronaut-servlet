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
import io.micronaut.http.server.exceptions.HttpServerException;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.impl.io.ChunkedOutputStream;
import org.apache.hc.core5.http.impl.io.ContentLengthOutputStream;
import org.apache.hc.core5.http.impl.io.DefaultHttpResponseWriter;
import org.apache.hc.core5.http.impl.io.SessionOutputBufferImpl;
import org.apache.hc.core5.http.io.SessionOutputBuffer;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

final class ApacheResponseContext implements Closeable {
    boolean connectionClose = false;
    ApacheServletHttpResponse<?> primaryResponse;

    private final SessionOutputBuffer outputBuffer;
    private final OutputStream out;
    private OutputStream bodyStream;

    ApacheResponseContext(ApacheServletConfiguration configuration, OutputStream out) {
        this.out = out;
        outputBuffer = new SessionOutputBufferImpl(configuration.outputBufferSize());
    }

    boolean isCommitted() {
        return bodyStream != null;
    }

    OutputStream commit(ClassicHttpResponse headers) throws IOException {
        if (isCommitted()) {
            throw new IllegalStateException("Response has already been committed");
        }

        headers.removeHeaders(HttpHeaders.TRANSFER_ENCODING);
        Header contentLengthStr = headers.getFirstHeader(HttpHeaders.CONTENT_LENGTH);
        long contentLength = contentLengthStr == null ? -1 : Long.parseLong(contentLengthStr.getValue());
        if (contentLength < 0) {
            headers.removeHeaders(HttpHeaders.CONTENT_LENGTH);
            headers.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
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

        OutputStream s = contentLength < 0 ? new ChunkedOutputStream(outputBuffer, out, 0) : new ContentLengthOutputStream(outputBuffer, out, contentLength);
        bodyStream = s;
        return s;
    }

    @Override
    public void close() throws IOException {
        if (bodyStream != null) {
            bodyStream.flush();
            bodyStream.close();
        }
        outputBuffer.flush(out);

        if (connectionClose) {
            out.close();
        }
    }
}
