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
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.io.buffer.ByteBufferFactory;
import io.micronaut.core.util.SupplierUtil;
import io.micronaut.http.uri.QueryStringDecoder;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpParameters;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.stream.InputStreamByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.poja.PojaConnection;
import io.micronaut.http.poja.PojaHttpRequest;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.http.body.stream.BodySizeLimits;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.servlet.http.LimitedInputStream;
import reactor.core.publisher.Flux;
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
import org.apache.hc.core5.http.NameValuePair;
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
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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

    private final ClassicHttpRequest request;
    private final ApacheResponseContext responseContext;

    private final HttpMethod method;
    private URI uri;
    private final MultiValueHeaders headers;
    private final MultiValuesQueryParameters queryParameters;
    /**
     * The parameters, resolved when first asked for: those of a form are read from the body.
     */
    private @Nullable MultiValuesQueryParameters parameters;
    private Supplier<MultiValuesQueryParameters> parametersSource;
    /**
     * The cookies of the {@code Cookie} headers they were parsed from, parsed again once the headers change, e.g.
     * when a filter adds a cookie to a request wrapper.
     */
    private SimpleCookies cookies;
    private List<String> cookieHeaders;
    /**
     * The cookies added with {@link #cookie(Cookie)}, which stay whatever the headers.
     */
    private final Map<String, Cookie> addedCookies = new LinkedHashMap<>();

    /**
     * The body, created when first asked for: a small body is then read whole, like on the servlet engine.
     */
    private final Supplier<ByteBody> byteBody;
    /**
     * The body as the framing delimits it, which has to be consumed before the next request on the connection.
     */
    private final InputStream framedBody;
    private final ByteBodyFactory byteBodyFactory;
    private @Nullable ContentLengthExceededException bodyTooLarge;

    private ApacheServletHttpResponse<?> primaryResponse;

    /**
     * Create an Apache-based request.
     *
     * @param inputStream The input stream
     * @param responseContext The response context
     * @param sessionInputBuffer Input buffer for parsing
     * @param conversionService The conversion service
     * @param messageBodyHandlerRegistry The message body handler registry
     * @param ioExecutor The executor service
     * @param byteBufferFactory The byte buffer factory
     */
    public ApacheServletHttpRequest(
        InputStream inputStream,
        ApacheResponseContext responseContext,
        SessionInputBuffer sessionInputBuffer,
        ConversionService conversionService,
        MessageBodyHandlerRegistry messageBodyHandlerRegistry,
        ExecutorService ioExecutor,
        ByteBufferFactory<?, ?> byteBufferFactory
    ) {
        this(inputStream, responseContext, sessionInputBuffer, conversionService, messageBodyHandlerRegistry, ioExecutor,
            byteBufferFactory, PojaConnection.UNKNOWN, BodySizeLimits.UNLIMITED);
    }

    /**
     * Create an Apache-based request, on a connection whose addresses are known, applying the server's body size limits.
     *
     * @param inputStream The input stream
     * @param responseContext The response context
     * @param sessionInputBuffer Input buffer for parsing
     * @param conversionService The conversion service
     * @param messageBodyHandlerRegistry The message body handler registry
     * @param ioExecutor The executor service
     * @param byteBufferFactory The byte buffer factory
     * @param connection The addresses of the connection
     * @param bodySizeLimits The limits from {@code micronaut.server.max-request-size} and
     *                       {@code micronaut.server.max-request-buffer-size}
     * @since 6.2.0
     */
    @SuppressWarnings("java:S107")
    public ApacheServletHttpRequest(
        InputStream inputStream,
        ApacheResponseContext responseContext,
        SessionInputBuffer sessionInputBuffer,
        ConversionService conversionService,
        MessageBodyHandlerRegistry messageBodyHandlerRegistry,
        ExecutorService ioExecutor,
        ByteBufferFactory<?, ?> byteBufferFactory,
        PojaConnection connection,
        BodySizeLimits bodySizeLimits
    ) {
        super(conversionService, messageBodyHandlerRegistry, connection);
        this.responseContext = responseContext;
        DefaultHttpRequestParser parser = new DefaultHttpRequestParser();

        try {
            request = parser.parse(sessionInputBuffer, inputStream);
        } catch (HttpException | IOException e) {
            throw new ApacheServletBadRequestException("HTTP request could not be parsed", e);
        }
        if (request == null) {
            throw new NoPojaRequestException();
        }

        method = HttpMethod.parse(request.getMethod());
        responseContext.headRequest = method == HttpMethod.HEAD;
        try {
            uri = request.getUri();
        } catch (URISyntaxException e) {
            throw new ApacheServletBadRequestException("Could not get request URI", e);
        }
        headers = createHeaders(request.getHeaders(), conversionService);
        queryParameters = parseQueryParameters(uri, conversionService);
        cookieHeaders = new ArrayList<>(headers.getAll(HttpHeaders.COOKIE));
        cookies = parseCookies(cookieHeaders, conversionService);
        parametersSource = this::resolveParameters;

        Header connectionHeader = request.getFirstHeader(HttpHeaders.CONNECTION);
        if (connectionHeader != null && connectionHeader.getValue().equalsIgnoreCase(CONNECTION_CLOSE)) {
            responseContext.connectionClose = true;
        }

        long contentLength = getContentLength();
        if (!getMethod().permitsRequestBody()) {
            contentLength = 0;
        }
        OptionalLong optionalContentLength = contentLength >= 0 ? OptionalLong.of(contentLength) : OptionalLong.empty();
        InputStream bodyStream = createBodyStream(inputStream, contentLength, sessionInputBuffer);
        this.framedBody = bodyStream;
        byteBodyFactory = ByteBodyFactory.createDefault(byteBufferFactory);
        long maxBodySize = bodySizeLimits.maxBodySize();
        if (contentLength > maxBodySize) {
            // refused without reading a byte: every read of the body fails, so a route that binds it answers 413
            // as on the Netty server, and the unread bytes close the connection
            ContentLengthExceededException tooLarge = new ContentLengthExceededException(maxBodySize, contentLength);
            bodyTooLarge = tooLarge;
            responseContext.connectionClose = true;
            byteBody = SupplierUtil.memoized(() -> byteBodyFactory.adapt(Flux.error(tooLarge), optionalContentLength));
        } else {
            if (contentLength < 0 && maxBodySize < Long.MAX_VALUE) {
                // a chunked body cannot grow past the limit either
                bodyStream = new LimitedInputStream(bodyStream, maxBodySize);
            }
            InputStream limitedBody = bodyStream;
            long length = contentLength;
            byteBody = SupplierUtil.memoized(() -> {
                if (length > 0 && length <= bodySizeLimits.maxBufferSize()) {
                    // a body that fits the buffer is read whole on the request thread, like on the servlet engine
                    try {
                        return byteBodyFactory.copyOf(limitedBody);
                    } catch (IOException e) {
                        throw new InternalServerException("Error reading request body: " + e.getMessage(), e);
                    }
                }
                // read through the size limits, like on the servlet engine: what is buffered to decode the body in
                // memory is bounded by micronaut.server.max-request-buffer-size
                return byteBodyFactory.adapt(
                    InputStreamByteBody.create(limitedBody, optionalContentLength, ioExecutor, byteBodyFactory).toReadBufferPublisher(),
                    bodySizeLimits, headers, null);
            });
        }
        primaryResponse = new ApacheServletHttpResponse<>(responseContext, conversionService);
    }

    /**
     * Create body stream.
     * Based on org.apache.hc.core5.http.impl.io.BHttpConnectionBase#createContentOutputStream.
     *
     * @param inputStream The input stream
     * @param contentLength The content length
     * @param sessionInputBuffer The input buffer
     * @return The body stream
     */
    private InputStream createBodyStream(InputStream inputStream, long contentLength, SessionInputBuffer sessionInputBuffer) {
        InputStream bodyStream;
        if (contentLength > 0) {
            bodyStream = new ContentLengthInputStream(sessionInputBuffer, inputStream, contentLength);
        } else if (contentLength == 0) {
            bodyStream = EmptyInputStream.INSTANCE;
        } else if (TRANSFER_ENCODING_CHUNKED.equalsIgnoreCase(headers.get(HttpHeaders.TRANSFER_ENCODING))) {
            bodyStream = new ChunkedInputStream(sessionInputBuffer, inputStream);
        } else {
            bodyStream = EmptyInputStream.INSTANCE;
        }
        return bodyStream;
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
        List<String> current = headers.getAll(HttpHeaders.COOKIE);
        if (!current.equals(cookieHeaders)) {
            cookieHeaders = new ArrayList<>(current);
            cookies = parseCookies(current, conversionService);
            addedCookies.values().forEach(cookie -> cookies.put(cookie.getName(), cookie));
        }
        return cookies;
    }

    @Override
    public @NonNull MutableHttpParameters getParameters() {
        MultiValuesQueryParameters current = parameters;
        if (current == null) {
            current = parametersSource.get();
            parameters = current;
        }
        return current;
    }

    @Override
    public @NonNull HttpMethod getMethod() {
        return method;
    }

    @Override
    protected String getNativeMethodName() {
        return request.getMethod();
    }

    @Override
    public void close() {
        runDisposalResources();
    }

    @Override
    public boolean abortResponse(Throwable failure) {
        responseContext.abort();
        return true;
    }

    /**
     * Consumes what the route left of the body, so that the next request on the connection starts at its own
     * request line; a body over the size limit is not read, and the connection is closed instead.
     */
    void discardUnreadBody() {
        if (bodyTooLarge != null || responseContext.connectionClose) {
            responseContext.connectionClose = true;
            return;
        }
        try {
            // closing the framed stream reads it to its end: the declared length, or the last chunk
            framedBody.close();
        } catch (IOException | RuntimeException e) {
            responseContext.connectionClose = true;
        }
    }

    @Override
    public @NonNull URI getUri() {
        return uri;
    }

    @Override
    public MutableHttpRequest<B> cookie(Cookie cookie) {
        addedCookies.put(cookie.getName(), cookie);
        // brought up to date with the headers first
        getCookies();
        cookies.put(cookie.getName(), cookie);
        return this;
    }

    @Override
    public MutableHttpRequest<B> uri(URI uri) {
        // the query parameters follow the URI: those of the previous URI are replaced, the fields of a form kept.
        // Applied when the parameters are first read, so that the form is not read for a change of the URI
        Map<String, List<String>> previousQuery = new QueryStringDecoder(getUri()).parameters();
        Map<String, List<String>> newQuery = new QueryStringDecoder(uri).parameters();
        MultiValuesQueryParameters current = parameters;
        Supplier<MultiValuesQueryParameters> base = current != null ? () -> current : parametersSource;
        this.parametersSource = () -> {
            Map<CharSequence, List<String>> values = new LinkedHashMap<>();
            newQuery.forEach((name, list) -> values.put(name, new ArrayList<>(list)));
            MultiValuesQueryParameters previous = base.get();
            for (String name : previous.names()) {
                List<String> rest = new ArrayList<>(previous.getAll(name));
                previousQuery.getOrDefault(name, List.of()).forEach(rest::remove);
                if (!rest.isEmpty()) {
                    values.computeIfAbsent(name, k -> new ArrayList<>()).addAll(rest);
                }
            }
            return new MultiValuesQueryParameters(values, conversionService);
        };
        this.parameters = null;
        this.uri = uri;
        return this;
    }

    @Override
    public @NonNull MutableHttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public @NonNull ByteBody byteBody() {
        return byteBody.get();
    }

    @Override
    public @NonNull ByteBodyFactory byteBodyFactory() {
        return byteBodyFactory;
    }

    @Override
    public void setConversionService(@NonNull ConversionService conversionService) {
        // Not implemented
    }

    private static SimpleCookies parseCookies(List<String> cookieHeaders, ConversionService conversionService) {
        SimpleCookies cookies = new SimpleCookies(conversionService);

        // Manually parse cookies from the response headers
        for (String cookie : cookieHeaders) {

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
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (Header header: headers) {
            if (!map.containsKey(header.getName())) {
                map.put(header.getName(), new ArrayList<>(1));
            }
            map.get(header.getName()).add(header.getValue());
        }
        return new MultiValueHeaders(map, conversionService);
    }

    private static MultiValuesQueryParameters parseQueryParameters(URI uri, ConversionService conversionService) {
        Map<CharSequence, List<String>> map = new URIBuilder(uri).getQueryParams().stream()
            .collect(Collectors.groupingBy(
                    NameValuePair::getName,
                    Collectors.mapping(NameValuePair::getValue, Collectors.toList())
            ));
        return new MultiValuesQueryParameters(map, conversionService);
    }

    /**
     * The parameters of the request: the query parameters, and the fields of a URL encoded form.
     */
    private MultiValuesQueryParameters resolveParameters() {
        Map<CharSequence, List<String>> merged = new LinkedHashMap<>();
        for (String name : queryParameters.names()) {
            List<String> values = queryParameters.getAll(name);
            if (!values.isEmpty()) {
                merged.put(name, new ArrayList<>(values));
            }
        }
        MediaType contentType = getContentType().orElse(null);
        if (contentType == null || !contentType.matches(MediaType.APPLICATION_FORM_URLENCODED_TYPE) || isBodySet()) {
            return new MultiValuesQueryParameters(merged, conversionService);
        }
        if (bodyTooLarge != null) {
            // the fields are read from the body, which is over the limit
            throw bodyTooLarge;
        }
        ConvertibleMultiValues<CharSequence> formData = getFormData();
        for (String name : formData.names()) {
            List<CharSequence> values = formData.getAll(name);
            if (values == null || values.isEmpty()) {
                merged.computeIfAbsent(name, key -> new ArrayList<>());
                continue;
            }
            List<String> target = merged.computeIfAbsent(name, key -> new ArrayList<>());
            for (CharSequence value : values) {
                target.add(Objects.requireNonNull(value, "form value").toString());
            }
        }
        return new MultiValuesQueryParameters(merged, conversionService);
    }

    /**
     * An input stream that would initially delegate to the first input stream
     * and then to the second one. Created specifically to be used with {@link ByteBody}.
     */
    private static final class CombinedInputStream extends InputStream {

        private final InputStream first;
        private final InputStream second;
        private boolean finishedFirst;

        /**
         * Create the input stream from first stream and second stream.
         *
         * @param first The first stream
         * @param second The second stream
         */
        CombinedInputStream(InputStream first, InputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public int read() throws IOException {
            if (finishedFirst) {
                return second.read();
            }
            int result = first.read();
            if (result == -1) {
                finishedFirst = true;
                return second.read();
            }
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (finishedFirst) {
                return second.read(b, off, len);
            }
            int readLength = first.read(b, off, len);
            if (readLength < len) {
                finishedFirst = true;
                readLength += second.read(b, off + readLength, len - readLength);
            }
            return readLength;
        }

        @Override
        public void close() throws IOException {
            first.close();
            second.close();
        }
    }

}
