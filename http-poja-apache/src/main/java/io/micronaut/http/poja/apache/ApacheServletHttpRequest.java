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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleMultiValuesMap;
import io.micronaut.core.io.buffer.ByteBufferFactory;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpParameters;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.stream.InputStreamByteBody;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.poja.PojaHttpRequest;
import io.micronaut.http.poja.apache.exception.ApacheServletBadRequestException;
import io.micronaut.http.poja.exception.NoPojaRequestException;
import io.micronaut.http.poja.util.MultiValueHeaders;
import io.micronaut.http.poja.util.MultiValuesQueryParameters;
import io.micronaut.http.simple.cookies.SimpleCookies;
import io.micronaut.servlet.http.ServletHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.io.ChunkedInputStream;
import org.apache.hc.core5.http.impl.io.ContentLengthInputStream;
import org.apache.hc.core5.http.impl.io.DefaultHttpRequestParser;
import org.apache.hc.core5.http.io.SessionInputBuffer;
import org.apache.hc.core5.http.io.entity.EmptyInputStream;
import org.apache.hc.core5.net.URIBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * An implementation of the POJA Http Request based on Apache.
 *
 * @param <B> Body type
 * @author Andriy Dmytruk
 * @since 4.10.0
 */
@Internal
public final class ApacheServletHttpRequest<B> extends PojaHttpRequest<B, ClassicHttpRequest, ClassicHttpResponse> {

    private static final String TRANSFER_ENCODING_CHUNKED = "chunked";
    private static final String CONNECTION_CLOSE = "close";
    private static final String EXPECT_CONTINUE = "100-continue";

    private final ClassicHttpRequest request;
    private final ApacheResponseContext responseContext;

    private final HttpMethod method;
    private URI uri;
    private final MultiValueHeaders headers;
    private final MultiValuesQueryParameters queryParameters;
    private final SimpleCookies cookies;

    private final CloseableByteBody byteBody;

    private final @Nullable ApacheRequestBodyInputStream bodyStream;

    private ApacheServletHttpResponse<?> primaryResponse;

    /**
     * Create an Apache-based request with the default line and header limits and no body size limit.
     *
     * @param inputStream The input stream
     * @param responseContext The response context
     * @param sessionInputBuffer Input buffer for parsing
     * @param conversionService The conversion service
     * @param codecRegistry The media codec registry
     * @param ioExecutor The executor service
     * @param byteBufferFactory The byte buffer factory
     */
    public ApacheServletHttpRequest(
        InputStream inputStream,
        ApacheResponseContext responseContext,
        SessionInputBuffer sessionInputBuffer,
        ConversionService conversionService,
        MediaTypeCodecRegistry codecRegistry,
        ExecutorService ioExecutor,
        ByteBufferFactory<?, ?> byteBufferFactory
    ) {
        this(inputStream, responseContext, sessionInputBuffer, conversionService, codecRegistry,
            ioExecutor, byteBufferFactory, Http1Config.DEFAULT, -1);
    }

    /**
     * Create an Apache-based request.
     *
     * @param inputStream The input stream
     * @param responseContext The response context
     * @param sessionInputBuffer Input buffer for parsing
     * @param conversionService The conversion service
     * @param codecRegistry The media codec registry
     * @param ioExecutor The executor service
     * @param byteBufferFactory The byte buffer factory
     * @param http1Config The HTTP/1.1 parsing limits
     * @param maxBodySize The maximum body size ({@code micronaut.server.max-request-size}), negative for no limit
     */
    public ApacheServletHttpRequest(
        InputStream inputStream,
        ApacheResponseContext responseContext,
        SessionInputBuffer sessionInputBuffer,
        ConversionService conversionService,
        MediaTypeCodecRegistry codecRegistry,
        ExecutorService ioExecutor,
        ByteBufferFactory<?, ?> byteBufferFactory,
        Http1Config http1Config,
        long maxBodySize
    ) {
        super(conversionService, codecRegistry);
        this.responseContext = responseContext;
        DefaultHttpRequestParser parser = new DefaultHttpRequestParser(http1Config);

        try {
            request = parser.parse(sessionInputBuffer, inputStream);
        } catch (HttpException | IOException e) {
            throw new ApacheServletBadRequestException("HTTP request could not be parsed", e);
        }
        if (request == null) {
            throw new NoPojaRequestException();
        }
        if (isHttp10(request)) {
            // An HTTP/1.0 connection is not persistent (the response does not announce keep-alive), and a response
            // body without a length is delimited by closing it. Set before anything can fail, so a rejection is
            // framed the same way.
            responseContext.connectionClose = true;
            responseContext.closeDelimited = true;
        }

        // Frame the body before anything else can fail, following RFC 9112 section 6.3. A request whose framing is
        // ambiguous is rejected (and the connection closed by the caller), because where its body ends, and so
        // where the next request starts, cannot be known.
        // an unsupported expectation is rejected whether or not the request has a body
        boolean expectsContinue = expectsContinue(request);
        boolean chunked = isChunked(request);
        long contentLength = chunked ? -1 : parseContentLength(request);
        if (maxBodySize >= 0 && contentLength > maxBodySize) {
            throw new ApacheServletBadRequestException(
                "The content length [" + contentLength + "] exceeds the maximum allowed content length [" + maxBodySize + "]",
                null, HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        }
        InputStream framedStream;
        if (chunked) {
            framedStream = new ChunkedInputStream(sessionInputBuffer, inputStream, http1Config);
        } else if (contentLength > 0) {
            framedStream = new ContentLengthInputStream(sessionInputBuffer, inputStream, contentLength);
        } else {
            framedStream = null;
        }
        bodyStream = framedStream == null ? null : new ApacheRequestBodyInputStream(
            framedStream, maxBodySize, expectsContinue ? responseContext::sendContinue : null);

        method = HttpMethod.parse(request.getMethod());
        try {
            uri = request.getUri();
        } catch (URISyntaxException e) {
            throw new ApacheServletBadRequestException("Could not get request URI", e);
        }
        headers = createHeaders(request.getHeaders(), conversionService);
        queryParameters = parseQueryParameters(uri, conversionService);
        cookies = parseCookies(request, conversionService);

        Header connection = request.getFirstHeader(HttpHeaders.CONNECTION);
        if (connection != null && connection.getValue().equalsIgnoreCase(CONNECTION_CLOSE)) {
            responseContext.connectionClose = true;
        }

        // bodies are framed for every method: a body declared on a GET has to be consumed like any other
        OptionalLong optionalContentLength = chunked ? OptionalLong.empty() : OptionalLong.of(Math.max(contentLength, 0));
        byteBody = InputStreamByteBody.create(
            bodyStream == null ? EmptyInputStream.INSTANCE : bodyStream,
            optionalContentLength, ioExecutor, ByteBodyFactory.createDefault(byteBufferFactory));
        primaryResponse = new ApacheServletHttpResponse<>(responseContext, conversionService);
    }

    private static boolean isHttp10(ClassicHttpRequest request) {
        ProtocolVersion version = request.getVersion();
        return version != null && !version.greaterEquals(HttpVersion.HTTP_1_1);
    }

    private static boolean expectsContinue(ClassicHttpRequest request) {
        if (isHttp10(request)) {
            // RFC 9110 section 10.1.1: the expectation is ignored on an HTTP/1.0 request
            return false;
        }
        Header[] expectations = request.getHeaders(HttpHeaders.EXPECT);
        if (expectations.length == 0) {
            return false;
        }
        for (String expectation : headerTokens(expectations)) {
            if (!EXPECT_CONTINUE.equalsIgnoreCase(expectation)) {
                throw new ApacheServletBadRequestException("Unsupported expectation", null, HttpStatus.EXPECTATION_FAILED);
            }
        }
        return true;
    }

    private static boolean isChunked(ClassicHttpRequest request) {
        Header[] transferEncodings = request.getHeaders(HttpHeaders.TRANSFER_ENCODING);
        if (transferEncodings.length == 0) {
            return false;
        }
        if (request.getHeaders(HttpHeaders.CONTENT_LENGTH).length > 0) {
            throw new ApacheServletBadRequestException("Request must not carry both Content-Length and Transfer-Encoding");
        }
        // chunked has to be the final coding, and no other coding is supported, so it has to be the only one
        List<String> codings = headerTokens(transferEncodings);
        if (codings.size() != 1 || !TRANSFER_ENCODING_CHUNKED.equalsIgnoreCase(codings.get(0))) {
            throw new ApacheServletBadRequestException("Unsupported Transfer-Encoding");
        }
        return true;
    }

    private static long parseContentLength(ClassicHttpRequest request) {
        Header[] contentLengths = request.getHeaders(HttpHeaders.CONTENT_LENGTH);
        if (contentLengths.length == 0) {
            return -1;
        }
        List<String> values = headerTokens(contentLengths);
        if (values.size() != 1) {
            throw new ApacheServletBadRequestException("Invalid Content-Length");
        }
        String value = values.get(0);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new ApacheServletBadRequestException("Invalid Content-Length");
            }
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ApacheServletBadRequestException("Invalid Content-Length", e);
        }
    }

    private static List<String> headerTokens(Header[] headers) {
        List<String> tokens = new ArrayList<>(headers.length);
        for (Header header : headers) {
            String value = header.getValue();
            if (value == null) {
                tokens.add("");
                continue;
            }
            for (String token : value.split(",", -1)) {
                tokens.add(token.trim());
            }
        }
        return tokens;
    }

    /**
     * Consume what the application left unread of the body, so that the next request on the session stream is
     * parsed from where this body ends.
     *
     * @return {@code true} if the body was consumed and another request can follow, {@code false} if the
     * connection has to be closed
     */
    boolean discardBody() {
        return bodyStream == null || bodyStream.discardRemaining();
    }

    /**
     * Take the body away from the application when the connection is closed rather than reused.
     *
     * @return Whether the connection input may be read directly, because no application read is in progress
     */
    boolean detachBody() {
        return bodyStream == null || bodyStream.tryDetach();
    }

    /**
     * @return Whether part of the body has not been read yet
     */
    boolean hasUnreadBody() {
        return bodyStream != null && !bodyStream.isFinished();
    }

    @Override
    public void close() {
        byteBody.close();
    }

    @Override
    public ServletHttpResponse<ClassicHttpResponse, ?> getResponse() {
        return primaryResponse;
    }

    @Override
    public ServletHttpResponse<ClassicHttpResponse, ?> createResponse() {
        ApacheServletHttpResponse<Object> r = new ApacheServletHttpResponse<>(responseContext, conversionService);
        primaryResponse = r;
        return r;
    }

    @Override
    public ClassicHttpRequest getNativeRequest() {
        return request;
    }

    @Override
    public @NonNull Cookies getCookies() {
        return cookies;
    }

    @Override
    public @NonNull MutableHttpParameters getParameters() {
        return queryParameters;
    }

    @Override
    public @NonNull HttpMethod getMethod() {
        return method;
    }

    @Override
    public @NonNull URI getUri() {
        return uri;
    }

    @Override
    public MutableHttpRequest<B> cookie(Cookie cookie) {
        cookies.put(cookie.getName(), cookie);
        return this;
    }

    @Override
    public MutableHttpRequest<B> uri(URI uri) {
        this.uri = uri;
        return this;
    }

    @Override
    public <T> MutableHttpRequest<T> body(T body) {
        throw new UnsupportedOperationException("Could not change request body");
    }

    @Override
    public @NonNull MutableHttpHeaders getHeaders() {
        return headers;
    }

    @Override
    @SuppressWarnings("unchecked")
    public @NonNull Optional<B> getBody() {
        return (Optional<B>) getBody(Object.class);
    }

    @Override
    public @NonNull ByteBody byteBody() {
        return byteBody;
    }

    @Override
    public void setConversionService(@NonNull ConversionService conversionService) {
        // Not implemented
    }

    private SimpleCookies parseCookies(ClassicHttpRequest request, ConversionService conversionService) {
        SimpleCookies cookies = new SimpleCookies(conversionService);

        // Manually parse cookies from the response headers
        for (Header header : request.getHeaders(HttpHeaders.COOKIE)) {
            String cookie = header.getValue();

            String name = null;
            int start = 0;
            for (int i = 0; i < cookie.length(); ++i) {
                if (i < cookie.length() - 1 && cookie.charAt(i) == ';' && cookie.charAt(i + 1) == ' ') {
                    if (name != null) {
                        cookies.put(name, Cookie.of(name, cookie.substring(start, i)));
                        name = null;
                        start = i + 2;
                        ++i;
                    }
                } else if (cookie.charAt(i) == '=') {
                    name = cookie.substring(start, i);
                    start = i + 1;
                }
            }
            if (name != null) {
                cookies.put(name, Cookie.of(name, cookie.substring(start)));
            }
        }
        return cookies;
    }

    private static MultiValueHeaders createHeaders(
        Header[] headers, ConversionService conversionService
    ) {
        // add standardizes each name, so the headers are grouped once and names differing in case are merged
        MultiValueHeaders result = new MultiValueHeaders(new MutableConvertibleMultiValuesMap<>(new LinkedHashMap<>(), conversionService));
        for (Header header: headers) {
            result.add(header.getName(), header.getValue());
        }
        return result;
    }

    private static MultiValuesQueryParameters parseQueryParameters(URI uri, ConversionService conversionService) {
        Map<CharSequence, List<String>> map = new URIBuilder(uri).getQueryParams().stream()
            .collect(Collectors.groupingBy(
                    NameValuePair::getName,
                    Collectors.mapping(NameValuePair::getValue, Collectors.toList())
            ));
        return new MultiValuesQueryParameters(map, conversionService);
    }

}
