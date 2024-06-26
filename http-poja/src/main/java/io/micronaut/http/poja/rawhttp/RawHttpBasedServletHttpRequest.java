/*
 * Copyright © 2024 Oracle and/or its affiliates.
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
package io.micronaut.http.poja.rawhttp;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpParameters;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.ByteBody.SplitBackpressureMode;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.poja.PojaHttpRequest;
import io.micronaut.http.simple.cookies.SimpleCookies;
import io.micronaut.servlet.http.body.InputStreamByteBody;
import rawhttp.cookies.ServerCookieHelper;
import rawhttp.core.RawHttp;
import rawhttp.core.RawHttpHeaders;
import rawhttp.core.RawHttpRequest;
import rawhttp.core.RawHttpResponse;
import rawhttp.core.body.BodyReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Sahoo.
 */
public class RawHttpBasedServletHttpRequest<B> extends PojaHttpRequest<B, RawHttpRequest, RawHttpResponse<Void>> {
    private final RawHttp rawHttp;
    private final RawHttpRequest rawHttpRequest;
    private final ByteBody byteBody;
    private final RawHttpBasedHeaders headers;
    private final RawHttpBasedParameters queryParameters;

    public RawHttpBasedServletHttpRequest(
        InputStream in,
        ConversionService conversionService,
        MediaTypeCodecRegistry codecRegistry,
        ExecutorService ioExecutor,
        RawHttpBasedServletHttpResponse<Void> response
    ) {
        super(conversionService, codecRegistry, (RawHttpBasedServletHttpResponse) response);
        this.rawHttp = new RawHttp();
        try {
            rawHttpRequest = rawHttp.parseRequest(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        headers = new RawHttpBasedHeaders(rawHttpRequest.getHeaders(), conversionService);
        OptionalLong contentLength = rawHttpRequest.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH)
            .map(Long::parseLong).map(OptionalLong::of).orElse(OptionalLong.empty());

        InputStream stream = rawHttpRequest.getBody()
            .map(BodyReader::asRawStream)
            .orElse(new ByteArrayInputStream(new byte[0]));
        this.byteBody = InputStreamByteBody.create(stream, contentLength, ioExecutor);
        queryParameters = new RawHttpBasedParameters(getUri().getRawQuery(), conversionService);
    }

    @Override
    public RawHttpRequest getNativeRequest() {
        return rawHttpRequest;
    }

    @Override
    public @NonNull Cookies getCookies() {
        Map<CharSequence, Cookie> cookiesMap = ServerCookieHelper.readClientCookies(rawHttpRequest)
            .stream()
            .map(RawHttpCookie::new)
            .collect(Collectors.toMap(Cookie::getName, Function.identity()));
        SimpleCookies cookies = new SimpleCookies(conversionService);
        cookies.putAll(cookiesMap);
        return cookies;
    }

    @Override
    public @NonNull HttpParameters getParameters() {
        return queryParameters;
    }

    @Override
    public @NonNull HttpMethod getMethod() {
        return HttpMethod.parse(rawHttpRequest.getMethod());
    }

    @Override
    public @NonNull URI getUri() {
        return rawHttpRequest.getUri();
    }

    @Override
    public @NonNull HttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public @NonNull Optional<B> getBody() {
        return (Optional<B>) getBody(Object.class);
    }

    @Override
    public @NonNull CloseableByteBody byteBody() {
        return byteBody.split(SplitBackpressureMode.FASTEST);
    }

    public record RawHttpCookie(
        HttpCookie cookie
    ) implements Cookie {

        @Override
        public @NonNull String getName() {
            return cookie.getName();
        }

        @Override
        public @NonNull String getValue() {
            return cookie.getValue();
        }

        @Override
        public @Nullable String getDomain() {
            return cookie.getDomain();
        }

        @Override
        public @Nullable String getPath() {
            return cookie.getPath();
        }

        @Override
        public boolean isHttpOnly() {
            return cookie.isHttpOnly();
        }

        @Override
        public boolean isSecure() {
            return cookie.getSecure();
        }

        @Override
        public long getMaxAge() {
            return cookie.getMaxAge();
        }

        @Override
        public @NonNull Cookie maxAge(long maxAge) {
            cookie.setMaxAge(maxAge);
            return this;
        }

        @Override
        public @NonNull Cookie value(@NonNull String value) {
            cookie.setValue(value);
            return this;
        }

        @Override
        public @NonNull Cookie domain(@Nullable String domain) {
            cookie.setDomain(domain);
            return this;
        }

        @Override
        public @NonNull Cookie path(@Nullable String path) {
            cookie.setPath(path);
            return this;
        }

        @Override
        public @NonNull Cookie secure(boolean secure) {
            cookie.setSecure(secure);
            return this;
        }

        @Override
        public @NonNull Cookie httpOnly(boolean httpOnly) {
            cookie.setHttpOnly(httpOnly);
            return this;
        }

        @Override
        public int compareTo(Cookie o) {
            int v = getName().compareTo(o.getName());
            if (v != 0) {
                return v;
            }

            v = compareNullableValue(getPath(), o.getPath());
            if (v != 0) {
                return v;
            }

            return compareNullableValue(getDomain(), o.getDomain());
        }

        private static int compareNullableValue(String first, String second) {
            if (first == null) {
                if (second != null) {
                    return -1;
                } else {
                    return 0;
                }
            } else if (second == null) {
                return 1;
            } else {
                return first.compareToIgnoreCase(second);
            }
        }
    }

    public static class RawHttpBasedHeaders implements HttpHeaders {
        private final RawHttpHeaders rawHttpHeaders;
        private final ConversionService conversionService;

        private RawHttpBasedHeaders(RawHttpHeaders rawHttpHeaders, ConversionService conversionService) {
            this.rawHttpHeaders = rawHttpHeaders;
            this.conversionService = conversionService;
        }

        @Override
        public List<String> getAll(CharSequence name) {
            return rawHttpHeaders.get(String.valueOf(name));
        }

        @Override
        public @Nullable String get(CharSequence name) {
            List<String> all = getAll(name);
            return all.isEmpty() ? null : all.get(0);
        }

        @Override
        public Set<String> names() {
            return rawHttpHeaders.getUniqueHeaderNames();
        }

        @Override
        public Collection<List<String>> values() {
            return rawHttpHeaders.asMap().values();
        }

        @Override
        public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
            String header = get(name);
            return header == null ? Optional.empty() : conversionService.convert(header, conversionContext);
        }
    }

    private static class RawHttpBasedParameters implements HttpParameters {
        private final Map<String, List<String>> queryParams;
        private final ConversionService conversionService;

        private RawHttpBasedParameters(String queryString, ConversionService conversionService) {
            queryParams = QueryParametersParser.parseQueryParameters(queryString);
            this.conversionService = conversionService;
        }

        @Override
        public List<String> getAll(CharSequence name) {
            return queryParams.get(name.toString());
        }

        @Override
        public @Nullable String get(CharSequence name) {
            List<String> all = getAll(name);
            return all == null || all.isEmpty() ? null : all.get(0);
        }

        @Override
        public Set<String> names() {
            return queryParams.keySet();
        }

        @Override
        public Collection<List<String>> values() {
            return queryParams.values();
        }

        @Override
        public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
            String header = get(name);
            return header == null ? Optional.empty() : conversionService.convert(header, conversionContext);
        }

        static class QueryParametersParser {
            public static Map<String, List<String>> parseQueryParameters(String queryParameters) {
                return queryParameters != null && !queryParameters.isEmpty() ?
                        Arrays.stream(queryParameters.split("[&;]"))
                                .map(QueryParametersParser::splitQueryParameter)
                                .collect(Collectors.groupingBy(Map.Entry::getKey,
                                        LinkedHashMap::new,
                                        Collectors.mapping(Map.Entry::getValue, Collectors.toList()))) :
                        Collections.emptyMap();
            }

            private static Map.Entry<String, String> splitQueryParameter(String parameter) {
                int idx = parameter.indexOf("=");
                String key = decode(idx > 0 ? parameter.substring(0, idx) : parameter);
                String value = idx > 0 && parameter.length() > idx + 1 ? decode(parameter.substring(idx + 1)) : "";
                return new AbstractMap.SimpleImmutableEntry<>(key, value);
            }

            private static String decode(String urlEncodedString) {
                return URLDecoder.decode(urlEncodedString, StandardCharsets.UTF_8);
            }
        }
    }
}
