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
package io.micronaut.http.poja.llhttp;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpParameters;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.poja.PojaHttpRequest;
import io.micronaut.http.poja.util.LimitingInputStream;
import io.micronaut.http.poja.util.MultiValueHeaders;
import io.micronaut.http.poja.util.MultiValuesQueryParameters;
import io.micronaut.http.simple.cookies.SimpleCookies;
import io.micronaut.servlet.http.body.InputStreamByteBody;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.impl.io.DefaultHttpRequestParser;
import org.apache.hc.core5.http.impl.io.SessionInputBufferImpl;
import org.apache.hc.core5.net.URIBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
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
public class ApacheServletHttpRequest<B> extends PojaHttpRequest<B, ClassicHttpRequest, ClassicHttpResponse> {

    private final ClassicHttpRequest request;

    private final HttpMethod method;
    private final URI uri;
    private final MultiValueHeaders headers;
    private final MultiValuesQueryParameters queryParameters;
    private final SimpleCookies cookies;

    private final ByteBody byteBody;

    /**
     * Create an Apache-based request.
     *
     * @param inputStream The input stream
     * @param conversionService The conversion service
     * @param codecRegistry The media codec registry
     * @param ioExecutor The executor service
     * @param response The response
     */
    public ApacheServletHttpRequest(
        InputStream inputStream,
        ConversionService conversionService,
        MediaTypeCodecRegistry codecRegistry,
        ExecutorService ioExecutor,
        ApacheServletHttpResponse<?> response
    ) {
        super(conversionService, codecRegistry, response);

        SessionInputBufferImpl sessionInputBuffer = new SessionInputBufferImpl(8192);
        DefaultHttpRequestParser parser = new DefaultHttpRequestParser();

        try {
            request = parser.parse(sessionInputBuffer, inputStream);
        } catch (HttpException | IOException e) {
            throw new RuntimeException("Could parse HTTP request", e);
        }

        method = HttpMethod.parse(request.getMethod());
        try {
            uri = request.getUri();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Could not get request URI", e);
        }
        headers = createHeaders(request.getHeaders(), conversionService);
        queryParameters = parseQueryParameters(uri, conversionService);
        cookies = parseCookies(request, conversionService);

        long contentLength = getContentLength();
        OptionalLong optionalContentLength = contentLength >= 0 ? OptionalLong.of(contentLength) : OptionalLong.empty();
        try {
            InputStream bodyStream = inputStream;
            if (sessionInputBuffer.length() > 0) {
                byte[] data = new byte[sessionInputBuffer.length()];
                sessionInputBuffer.read(data, inputStream);

                bodyStream = new CombinedInputStream(
                    new ByteArrayInputStream(data),
                    inputStream
                );
            }
            if (contentLength >= 0) {
                bodyStream = new LimitingInputStream(bodyStream, contentLength);
            } else {
                // Empty
                bodyStream = new ByteArrayInputStream(new byte[0]);
            }
            byteBody = InputStreamByteBody.create(
                bodyStream, optionalContentLength, ioExecutor
            );
        } catch (IOException e) {
            throw new RuntimeException("Could not get request body", e);
        }
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
        return null;
    }

    @Override
    public <T> MutableHttpRequest<T> body(T body) {
        return null;
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

    }

    private SimpleCookies parseCookies(ClassicHttpRequest request, ConversionService conversionService) {
        SimpleCookies cookies = new SimpleCookies(conversionService);

        // Manually parse cookies from the response headers
        for (Header header : request.getHeaders(MultiValueHeaders.COOKIE)) {
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
        Map<String, List<String>> map = new HashMap<>();
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
     * An input stream that would initially delegate to the first input stream
     * and then to the second one. Created specifically to be used with {@link ByteBody}.
     */
    private static class CombinedInputStream extends InputStream {

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
