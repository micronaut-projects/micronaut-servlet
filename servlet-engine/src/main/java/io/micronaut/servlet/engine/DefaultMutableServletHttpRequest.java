/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.servlet.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpParameters;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.http.simple.SimpleHttpParameters;
import io.micronaut.servlet.http.MutableServletHttpRequest;

/**
 * Mutable implementation for servlets.
 * @param <B> The body type
 */
@Internal
final class DefaultMutableServletHttpRequest<B> implements MutableServletHttpRequest<HttpServletRequest, B> {
    private final DefaultServletHttpRequest<B> servletHttpRequest;
    private URI uri;
    private ConversionService conversionService;
    private B body;
    private final MutableHttpParameters parameters;
    private final MutableHttpHeaders headers;

    DefaultMutableServletHttpRequest(DefaultServletHttpRequest<B> servletHttpRequest) {
        this.servletHttpRequest = servletHttpRequest;
        this.conversionService = servletHttpRequest.getConversionService();
        this.parameters = new SimpleHttpParameters(
            copyValues(servletHttpRequest.getParameters()),
            servletHttpRequest.getConversionService()
        );
        this.parameters.setConversionService(conversionService);
        SimpleHttpHeaders newHeaders = new SimpleHttpHeaders(
            new LinkedHashMap<>(),
            servletHttpRequest.getConversionService()
        );
        newHeaders.setConversionService(conversionService);
        servletHttpRequest.getHeaders().forEach((name, values) -> {
            for (String value : values) {
                newHeaders.add(name, value);
            }
        });
        this.headers = newHeaders;
    }

    private static Map<CharSequence, List<String>> copyValues(ConvertibleMultiValues<String> params) {
        LinkedHashMap<CharSequence, List<String>> values = new LinkedHashMap<>(params.names().size());
        params.forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        return values;
    }

    ConversionService getConversionService() {
        return conversionService;
    }

    @Override
    public MutableHttpRequest<B> cookie(Cookie cookie) {
        return this;
    }

    @Override
    public MutableHttpRequest<B> uri(URI uri) {
        this.uri = uri;
        return this;
    }

    @Override
    public <T> MutableHttpRequest<T> body(T body) {
        this.body = (B) body;
        return (MutableHttpRequest<T>) this;
    }

    @Override
    public MutableHttpHeaders getHeaders() {
        return this.headers;
    }

    @Override
    public MutableConvertibleValues<Object> getAttributes() {
        return servletHttpRequest.getAttributes();
    }

    @Override
    public Optional<B> getBody() {
        if (body != null) {
            return Optional.ofNullable(this.body);
        } else {
            return servletHttpRequest.getBody();
        }
    }

    @Override
    public Cookies getCookies() {
        return this.servletHttpRequest.getCookies();
    }

    @Override
    public MutableHttpParameters getParameters() {
        return this.parameters;
    }

    @Override
    public HttpMethod getMethod() {
        return servletHttpRequest.getMethod();
    }

    @Override
    public URI getUri() {
        if (uri != null) {
            return uri;
        }
        return servletHttpRequest.getUri();
    }

    @Override
    public void setConversionService(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (body instanceof InputStream in) {
            return in;
        }
        return servletHttpRequest.getInputStream();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (body instanceof InputStream in) {
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } else if (body instanceof BufferedReader reader) {
            return reader;
        } else if (body instanceof Reader r) {
            return new BufferedReader(r);
        }
        return servletHttpRequest.getReader();
    }

    @Override
    public HttpServletRequest getNativeRequest() {
        return servletHttpRequest.getNativeRequest();
    }
}
