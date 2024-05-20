package io.micronaut.http.poja;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpParameters;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.servlet.http.ServletHttpRequest;
import rawhttp.cookies.ServerCookieHelper;
import rawhttp.core.RawHttp;
import rawhttp.core.RawHttpHeaders;
import rawhttp.core.RawHttpRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Sahoo.
 */
class RawHttpBasedServletHttpRequest implements ServletHttpRequest<RawHttpRequest, Object> {
    private final RawHttp rawHttp;
    private final InputStream in;
    private final RawHttpRequest rawHttpRequest;
    private final RawHttpBasedHeaders headers;

    private final RawHttpBasedParameters queryParameters;

    public RawHttpBasedServletHttpRequest(InputStream in, ConversionService conversionService) {
        this.rawHttp = new RawHttp();
        this.in = in;
        try {
            rawHttpRequest = rawHttp.parseRequest(in);
//            System.err.println("DEBUG: Parsed following request from input stream: \n" + rawHttpRequest);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        headers = new RawHttpBasedHeaders(rawHttpRequest.getHeaders(), conversionService);
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
//        var cookies = ServerCookieHelper.readClientCookies(rawHttpRequest);
        // TODO
        throw new UnsupportedOperationException("TBD");
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
    public @NonNull Optional<Object> getBody() {
        // TODO: figure out what needs to be done.
        System.err.println("TBD: getBody() Retuning null body for now.");
        Thread.dumpStack();
        return Optional.empty();
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
