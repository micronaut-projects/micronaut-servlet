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
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpStatus;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link HttpServletResponse} implementation backed with a {@link ByteArrayOutputStream}.
 */
@Experimental
@Internal
final class HttpExchangeHttpServletResponse implements HttpServletResponse {
    private static final int DEFAULT_STATUS = HttpStatus.OK.getCode();
    private Map<String, List<Object>> headers = new LinkedHashMap<>();
    private final OutputStreamRequestedCallback callback;
    private final ServletOutputStream outputStream;
    private int status = DEFAULT_STATUS;
    private boolean committed = false;

    HttpExchangeHttpServletResponse(HttpExchange httpExchange, OutputStreamRequestedCallback callback) {
        this.outputStream = new HttpExchangeServletOutputStream(httpExchange);
        this.callback = callback;
    }

    @Override
    public void reset() {
        status = DEFAULT_STATUS;
        headers.clear();
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    /**
     *
     * @param committed  A committed response has already had its status code and headers written.
     */
    public void setCommitted(boolean committed) {
        this.committed = committed;
    }

    @Override
    public String getCharacterEncoding() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getContentType() {
        return getHeader(HttpHeaders.CONTENT_TYPE);
    }

    @Override
    public ServletOutputStream getOutputStream() {
        callback.onOutputStreamRequested(this);
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void setCharacterEncoding(String s) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void setContentLength(int i) {
        headers.put(HttpHeaders.CONTENT_LENGTH, Collections.singletonList(i));
    }

    @Override
    public void setContentLengthLong(long l) {
        headers.put(HttpHeaders.CONTENT_LENGTH, Collections.singletonList(l));
    }

    @Override
    public void setContentType(String s) {
        headers.put(HttpHeaders.CONTENT_TYPE, Collections.singletonList(s));
    }

    @Override
    public void setBufferSize(int i) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public int getBufferSize() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void flushBuffer() throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void resetBuffer() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void setLocale(Locale locale) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Locale getLocale() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void addCookie(Cookie cookie) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean containsHeader(String s) {
        return headers.containsKey(s);
    }

    @Override
    public String encodeURL(String s) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String encodeRedirectURL(String s) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void sendError(int i, String s) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void sendError(int i) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void sendRedirect(String s, int i, boolean b) throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void setDateHeader(String s, long l) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void addDateHeader(String s, long l) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void setHeader(String s, String s1) {
        headers.put(s, Collections.singletonList(s1));
    }

    @Override
    public void addHeader(String s, String s1) {
        headers.put(s, Collections.singletonList(s1));
    }

    @Override
    public void setIntHeader(String s, int i) {
        headers.put(s, Collections.singletonList(i));
    }

    @Override
    public void addIntHeader(String s, int i) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void setStatus(int i) {
        this.status = i;
    }

    @Override
    public int getStatus() {
        return this.status;
    }

    @Override
    public String getHeader(String s) {
        List<Object> headersValues = headers.get(s);
        return CollectionUtils.isEmpty(headersValues) ? null : headersValues.get(0).toString();
    }

    @Override
    public Collection<String> getHeaders(String s) {
        return headers.get(s).stream().map(Object::toString).toList();
    }

    @Override
    public Collection<String> getHeaderNames() {
        return headers.keySet();
    }
}
