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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleMultiValuesMap;
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
import io.micronaut.http.simple.cookies.SimpleCookies;
import io.micronaut.servlet.http.body.InputStreamByteBody;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.impl.io.DefaultHttpRequestParser;
import org.apache.hc.core5.http.impl.io.SessionInputBufferImpl;
import org.apache.hc.core5.net.URIBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * An implementation of the POJA Http Request based on Apache.
 *
 * @param <B> Body type
 * @author Andriy Dmytruk.
 */
public class ApacheServletHttpRequest<B> extends PojaHttpRequest<B, ClassicHttpRequest, ClassicHttpResponse> {

    private final ClassicHttpRequest request;

    private final HttpMethod method;
    private final URI uri;
    private final MultiValueHeaders headers;
    private final MultiValuesQueryParameters queryParameters;
    private final SimpleCookies cookies;

    private final ByteBody byteBody;

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
        headers = new MultiValueHeaders(request.getHeaders(), conversionService);
        queryParameters = new MultiValuesQueryParameters(uri, conversionService);
        cookies = parseCookies(request, conversionService);

        long contentLength = getContentLength();
        OptionalLong optionalContentLength = contentLength >= 0 ? OptionalLong.of(contentLength) : OptionalLong.empty();
        try {
            InputStream bodyStream = inputStream;
            if (sessionInputBuffer.available() > 0) {
                byte[] data = new byte[sessionInputBuffer.available()];
                sessionInputBuffer.read(data, inputStream);

                bodyStream = new CombinedInputStream(
                    new ByteArrayInputStream(data),
                    inputStream
                );
            }
            if (contentLength >= 0) {
                bodyStream = new LimitingInputStream(bodyStream, contentLength);
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
        return null;
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

    /**
     * Headers implementation.
     *
     * @param headers The values
     */
    public record MultiValueHeaders(
        MutableConvertibleMultiValuesMap<String> headers
    ) implements MutableHttpHeaders {

        public MultiValueHeaders(Header[] headers, ConversionService conversionService) {
            this(convertHeaders(headers, conversionService));
        }

        public MultiValueHeaders(Map<String, List<String>> headers, ConversionService conversionService) {
            this(standardizeHeaders(headers, conversionService));
        }

        @Override
        public List<String> getAll(CharSequence name) {
            return headers.getAll(standardizeHeader(name));
        }

        @Override
        public @Nullable String get(CharSequence name) {
            return headers.get(standardizeHeader(name));
        }

        @Override
        public Set<String> names() {
            return headers.names();
        }

        @Override
        public Collection<List<String>> values() {
            return headers.values();
        }

        @Override
        public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
            return headers.get(standardizeHeader(name), conversionContext);
        }

        @Override
        public MutableHttpHeaders add(CharSequence header, CharSequence value) {
            headers.add(standardizeHeader(header), value == null ? null : value.toString());
            return this;
        }

        @Override
        public MutableHttpHeaders remove(CharSequence header) {
            headers.remove(standardizeHeader(header));
            return this;
        }

        @Override
        public void setConversionService(@NonNull ConversionService conversionService) {
            this.headers.setConversionService(conversionService);
        }

        private static MutableConvertibleMultiValuesMap<String> convertHeaders(
            Header[] headers, ConversionService conversionService
        ) {
            Map<CharSequence, List<String>> map = new HashMap<>();
            for (Header header: headers) {
                String name = standardizeHeader(header.getName());
                if (!map.containsKey(name)) {
                    map.put(name, new ArrayList<>(1));
                }
                map.get(name).add(header.getValue());
            }
            return new MutableConvertibleMultiValuesMap<>(map, conversionService);
        }

        private static MutableConvertibleMultiValuesMap<String> standardizeHeaders(
            Map<String, List<String>> headers, ConversionService conversionService
        ) {
            MutableConvertibleMultiValuesMap<String> map
                = new MutableConvertibleMultiValuesMap<>(Collections.emptyMap(), conversionService);
            for (String key: headers.keySet()) {
                map.put(standardizeHeader(key), headers.get(key));
            }
            return map;
        }

        private static String standardizeHeader(CharSequence charSequence) {
            String s;
            if (charSequence == null) {
                return null;
            } else if (charSequence instanceof String) {
                s = (String) charSequence;
            } else {
                s = charSequence.toString();
            }

            StringBuilder result = new StringBuilder(s.length());
            boolean upperCase = true;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (upperCase && ('a' <= c && c <= 'z')) {
                    c = (char) (c - 32);
                }
                result.append(c);
                upperCase = c == '-';
            }
            return result.toString();
        }
    }

    /**
     * Query parameters implementation.
     *
     * @param queryParams The values
     */
    private record MultiValuesQueryParameters(
        MutableConvertibleMultiValuesMap<String> queryParams
    ) implements MutableHttpParameters {

        private MultiValuesQueryParameters(URI uri, ConversionService conversionService) {
            this(new MutableConvertibleMultiValuesMap<>(parseQueryParameters(uri), conversionService));
        }

        @Override
        public List<String> getAll(CharSequence name) {
            return queryParams.getAll(name);
        }

        @Override
        public @Nullable String get(CharSequence name) {
            return queryParams.get(name);
        }

        @Override
        public Set<String> names() {
            return queryParams.names();
        }

        @Override
        public Collection<List<String>> values() {
            return queryParams.values();
        }

        @Override
        public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
            return queryParams.get(name, conversionContext);
        }

        @Override
        public MutableHttpParameters add(CharSequence name, List<CharSequence> values) {
            for (CharSequence value: values) {
                queryParams.add(name, value == null ? null : value.toString());
            }
            return this;
        }

        @Override
        public void setConversionService(@NonNull ConversionService conversionService) {
            queryParams.setConversionService(conversionService);
        }

        public static Map<CharSequence, List<String>> parseQueryParameters(URI uri) {
            return new URIBuilder(uri).getQueryParams().stream()
                .collect(Collectors.groupingBy(
                    nameValuePair -> nameValuePair.getName(),
                    Collectors.mapping(nameValuePair -> nameValuePair.getValue(), Collectors.toList())
                ));
        }

    }
}
