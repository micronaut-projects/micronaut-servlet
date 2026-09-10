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
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.http.cookie.ServerCookieEncoder;
import io.micronaut.http.util.HttpHeadersUtil;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link HttpServletResponse} implementation backed by a {@link HttpExchange}.
 */
@Experimental
@Internal
final class HttpExchangeHttpServletResponse implements HttpServletResponse {
    private static final int DEFAULT_STATUS = HttpStatus.OK.getCode();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    /**
     * Header names are case-insensitive per RFC 9110, and a header may legitimately carry several values
     * (for example {@code Set-Cookie} or {@code Vary}).
     */
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final ResponseHeadersCommitter headersCommitter;
    private final HttpExchange httpExchange;
    private @Nullable ServletOutputStream outputStream;
    private int status = DEFAULT_STATUS;
    private boolean committed;
    private boolean headersSent;
    private boolean outputStreamRequested;

    HttpExchangeHttpServletResponse(HttpExchange httpExchange, ResponseHeadersCommitter headersCommitter) {
        this.httpExchange = httpExchange;
        this.headersCommitter = headersCommitter;
    }

    /**
     * Sends the status line and headers, unless they have already been sent for this response.
     *
     * @throws IOException If the headers could not be sent
     */
    void commitHeaders() throws IOException {
        if (headersSent) {
            return;
        }
        headersSent = true;
        headersCommitter.commit(this);
    }

    /**
     * @return Whether anything asked for the response body stream, which is how this response learns that a body is
     * about to be written
     */
    boolean isOutputStreamRequested() {
        return outputStreamRequested;
    }

    /**
     * @return Whether the response status permits a body at all. 1xx, 204, 205 and 304 never carry one, and neither
     * does a response to HEAD (RFC 9110)
     */
    boolean isBodyAllowed() {
        return status >= HttpStatus.OK.getCode()
            && status != HttpStatus.NO_CONTENT.getCode()
            && status != HttpStatus.RESET_CONTENT.getCode()
            && status != HttpStatus.NOT_MODIFIED.getCode()
            && !HttpMethod.HEAD.name().equalsIgnoreCase(httpExchange.getRequestMethod());
    }

    /**
     * @return The body length declared through {@code Content-Length}, or {@code -1} when it is absent or unparseable
     */
    long getDeclaredContentLength() {
        String value = getHeader(HttpHeaders.CONTENT_LENGTH);
        if (StringUtils.isEmpty(value)) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    @Override
    public void reset() {
        if (committed) {
            throw new IllegalStateException("Cannot reset a response that has already been committed");
        }
        status = DEFAULT_STATUS;
        headers.clear();
    }

    @Override
    public boolean isCommitted() {
        // deliberately not headersSent: this shim has no response buffer, so it sends headers as soon as the body
        // stream is asked for, which is earlier than a buffering container would commit. Reporting that as committed
        // makes the handler skip encoding a body it is about to write
        return committed;
    }

    /**
     * @param committed A committed response has already had its status code and headers written.
     */
    public void setCommitted(boolean committed) {
        this.committed = committed;
    }

    @Override
    public String getCharacterEncoding() {
        return HttpHeadersUtil.parseCharacterEncoding(getContentType(), null).name();
    }

    @Override
    public @Nullable String getContentType() {
        return getHeader(HttpHeaders.CONTENT_TYPE);
    }

    @Override
    public ServletOutputStream getOutputStream() {
        outputStreamRequested = true;
        try {
            commitHeaders();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (outputStream == null) {
            // a response that may not carry a body still has to tolerate writes; they are discarded rather than
            // corrupting an exchange that was announced as body-less
            outputStream = new HttpExchangeServletOutputStream(isBodyAllowed() ? httpExchange : null);
        }
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
        setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(i));
    }

    @Override
    public void setContentLengthLong(long l) {
        setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(l));
    }

    @Override
    public void setContentType(String s) {
        setHeader(HttpHeaders.CONTENT_TYPE, s);
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
        commitHeaders();
        if (outputStream != null) {
            outputStream.flush();
        }
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
        for (String encoded : ServerCookieEncoder.INSTANCE.encode(toMicronautCookie(cookie))) {
            addHeader(HttpHeaders.SET_COOKIE, encoded);
        }
    }

    private static io.micronaut.http.cookie.Cookie toMicronautCookie(Cookie cookie) {
        io.micronaut.http.cookie.Cookie result = io.micronaut.http.cookie.Cookie
            .of(cookie.getName(), cookie.getValue() == null ? "" : cookie.getValue())
            .httpOnly(cookie.isHttpOnly())
            .secure(cookie.getSecure());
        if (cookie.getDomain() != null) {
            result = result.domain(cookie.getDomain());
        }
        if (cookie.getPath() != null) {
            result = result.path(cookie.getPath());
        }
        if (cookie.getMaxAge() >= 0) {
            result = result.maxAge(cookie.getMaxAge());
        }
        String sameSite = cookie.getAttribute("SameSite");
        if (StringUtils.isNotEmpty(sameSite)) {
            for (SameSite candidate : SameSite.values()) {
                if (candidate.name().equalsIgnoreCase(sameSite)) {
                    result = result.sameSite(candidate);
                    break;
                }
            }
        }
        return result;
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
        setStatus(i);
        if (StringUtils.isNotEmpty(s)) {
            byte[] body = s.getBytes(StandardCharsets.UTF_8);
            setContentLength(body.length);
            getOutputStream().write(body);
        } else {
            commitHeaders();
        }
    }

    @Override
    public void sendError(int i) throws IOException {
        sendError(i, null);
    }

    @Override
    public void sendRedirect(String s, int i, boolean b) throws IOException {
        setStatus(i);
        setHeader(HttpHeaders.LOCATION, s);
        commitHeaders();
    }

    @Override
    public void setDateHeader(String s, long l) {
        setHeader(s, formatDate(l));
    }

    @Override
    public void addDateHeader(String s, long l) {
        addHeader(s, formatDate(l));
    }

    private static String formatDate(long millis) {
        return DATE_FORMAT.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
    }

    @Override
    public void setHeader(String s, @Nullable String s1) {
        if (s1 == null) {
            headers.remove(s);
        } else {
            List<String> values = new ArrayList<>(1);
            values.add(s1);
            headers.put(s, values);
        }
    }

    @Override
    public void addHeader(String s, @Nullable String s1) {
        if (s1 != null) {
            headers.computeIfAbsent(s, k -> new ArrayList<>(1)).add(s1);
        }
    }

    @Override
    public void setIntHeader(String s, int i) {
        setHeader(s, String.valueOf(i));
    }

    @Override
    public void addIntHeader(String s, int i) {
        addHeader(s, String.valueOf(i));
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
    public @Nullable String getHeader(String s) {
        List<String> headersValues = headers.get(s);
        return CollectionUtils.isEmpty(headersValues) ? null : headersValues.get(0);
    }

    @Override
    public Collection<String> getHeaders(String s) {
        List<String> values = headers.get(s);
        return CollectionUtils.isEmpty(values) ? Collections.emptyList() : Collections.unmodifiableList(values);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return Collections.unmodifiableCollection(headers.keySet());
    }
}
