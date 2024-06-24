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
package io.micronaut.http.poja;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.ConvertibleMultiValuesMap;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpParameters;
import io.micronaut.http.MediaType;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.ByteBody.SplitBackpressureMode;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.poja.fork.netty.QueryStringDecoder;
import io.micronaut.http.simple.cookies.SimpleCookies;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.body.InputStreamByteBody;
import rawhttp.cookies.ServerCookieHelper;
import rawhttp.core.RawHttp;
import rawhttp.core.RawHttpHeaders;
import rawhttp.core.RawHttpRequest;
import rawhttp.core.body.BodyReader;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Sahoo.
 */
class RawHttpBasedServletHttpRequest<B> implements ServletHttpRequest<RawHttpRequest, B>, ServerHttpRequest<B> {
    private final RawHttp rawHttp;
    private final RawHttpRequest rawHttpRequest;
    private final ByteBody byteBody;
    private final RawHttpBasedHeaders headers;

    private final ConversionService conversionService;
    private final MediaTypeCodecRegistry codecRegistry;
    private final RawHttpBasedParameters queryParameters;

    public RawHttpBasedServletHttpRequest(
        InputStream in, ConversionService conversionService, MediaTypeCodecRegistry codecRegistry, Executor ioExecutor
    ) {
        this.rawHttp = new RawHttp();
        try {
            rawHttpRequest = rawHttp.parseRequest(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        headers = new RawHttpBasedHeaders(rawHttpRequest.getHeaders(), conversionService);
        OptionalLong contentLength = rawHttpRequest.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH)
            .map(Long::parseLong).map(OptionalLong::of).orElse(OptionalLong.empty());

        this.byteBody = rawHttpRequest.getBody()
            .map(b -> InputStreamByteBody.create(b.asRawStream(), contentLength, ioExecutor))
            .orElse(InputStreamByteBody.create(new ByteArrayInputStream(new byte[0]), OptionalLong.of(0), ioExecutor));
        this.conversionService = conversionService;
        this.codecRegistry = codecRegistry;
        queryParameters = new RawHttpBasedParameters(getUri().getRawQuery(), conversionService);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return null;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return null;
//            return new BufferedReader(new InputStreamReader(getInputStream(),
//                    rawHttp.getOptions().getHttpHeadersOptions().getHeaderValuesCharset()));
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
    public @NonNull MutableConvertibleValues<Object> getAttributes() {
        // Attributes are used for sharing internal data and is not applicable in our case.
        // So, return empty map.
        return new MutableConvertibleValuesMap<>();
    }

    @Override
    public <T> @NonNull Optional<T> getBody(@NonNull ArgumentConversionContext<T> conversionContext) {
        Optional<? extends BodyReader> reader = rawHttpRequest.getBody();
        if (reader.isEmpty()) {
            return Optional.empty();
        }
        reader.get().asRawStream();

        Argument<T> arg = conversionContext.getArgument();
        if (arg == null) {
            return Optional.empty();
        }
        final Class<T> type = arg.getType();
        final MediaType contentType = getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);

        if (isFormSubmission()) {
            try (CloseableByteBody body = byteBody().split(SplitBackpressureMode.FASTEST)) {
                String content = IOUtils.readText(new BufferedReader(new InputStreamReader(
                    body.toInputStream(), getCharacterEncoding()
                )));
                ConvertibleMultiValues<?> form = parseFormData(content);
                if (ConvertibleValues.class == type || Object.class == type) {
                    return Optional.of((T) form);
                } else {
                    return conversionService.convert(form.asMap(), arg);
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to parse body", e);
            }
        }

        final MediaTypeCodec codec = codecRegistry.findCodec(contentType, type).orElse(null);
        if (codec == null) {
            return Optional.empty();
        }
        if (ConvertibleValues.class == type || Object.class == type) {
            final Map map = consumeBody(inputStream -> codec.decode(Map.class, inputStream));
            ConvertibleValues result = ConvertibleValues.of(map);
            return Optional.of((T) result);
        } else {
            final T value = consumeBody(inputStream -> codec.decode(arg, inputStream));
            return Optional.of(value);
        }
    }

    private ConvertibleMultiValues<CharSequence> parseFormData(String body) {
        Map parameterValues = new QueryStringDecoder(body, false).parameters();

        // Remove empty values
        Iterator<Entry<String, List<CharSequence>>> iterator = parameterValues.entrySet().iterator();
        while (iterator.hasNext()) {
            List<CharSequence> value = iterator.next().getValue();
            if (value.isEmpty() || StringUtils.isEmpty(value.get(0))) {
                iterator.remove();
            }
        }

        return new ConvertibleMultiValuesMap<CharSequence>(parameterValues, conversionService);
    }

    public boolean isFormSubmission() {
        MediaType contentType = getContentType().orElse(null);
        return MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(contentType)
            || MediaType.MULTIPART_FORM_DATA_TYPE.equals(contentType);
    }

    /**
     * A method that allows consuming body.
     *
     * @return The result
     * @param <T> The function return value
     */
    public <T> T consumeBody(Function<InputStream, T> consumer) {
        return consumer.apply(byteBody.split(SplitBackpressureMode.FASTEST).toInputStream());
    }

    @Override
    public @NonNull Optional<B> getBody() {
        // TODO: figure out what needs to be done.
        System.err.println("TBD: getBody() Retuning null body for now.");
        Thread.dumpStack();
        return Optional.empty();
    }

    @Override
    public @NonNull ByteBody byteBody() {
        return byteBody;
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
            return all.isEmpty() ? null : all.get(0);
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
