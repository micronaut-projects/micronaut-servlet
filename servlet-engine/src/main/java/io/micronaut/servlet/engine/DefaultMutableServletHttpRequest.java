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
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import jakarta.servlet.http.HttpServletRequest;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpVersion;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpParameters;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.DirectByteBodyAccess;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.RawFormField;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.http.simple.SimpleHttpParameters;
import io.micronaut.http.simple.cookies.SimpleCookies;
import io.micronaut.http.uri.QueryStringDecoder;
import io.micronaut.servlet.http.MutableServletHttpRequest;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import javax.net.ssl.SSLSession;

/**
 * Mutable implementation for servlets.
 * @param <B> The body type
 */
@Internal
final class DefaultMutableServletHttpRequest<B> implements MutableServletHttpRequest<HttpServletRequest, B>,
    ServerHttpRequest<B>, FormCapableHttpRequest<B>, DirectByteBodyAccess {
    private final DefaultServletHttpRequest<B> servletHttpRequest;
    private @Nullable URI uri;
    private ConversionService conversionService;
    private @Nullable B body;
    /**
     * Whether the body was set, even to {@code null}: then the body is that object, and not the
     * bytes of the request, which the routes no longer read.
     */
    private boolean bodySet;
    /**
     * The parameters, copied when first asked for: those of a form are read from the body, which may still be
     * read asynchronously when a filter creates this view, e.g. to change the URI.
     */
    private @Nullable MutableHttpParameters parameters;
    private Supplier<ConvertibleMultiValues<String>> parametersSource;
    /**
     * The cookies once one was added: those of the request are read-only, so they are copied on the first change.
     */
    private @Nullable SimpleCookies cookies;
    private final MutableHttpHeaders headers;

    DefaultMutableServletHttpRequest(DefaultServletHttpRequest<B> servletHttpRequest) {
        this(servletHttpRequest, servletHttpRequest.getConversionService(), servletHttpRequest::getParameters,
            servletHttpRequest.getHeaders());
    }

    /**
     * A view of a view, which keeps what the view changed.
     *
     * @param view The view
     */
    private DefaultMutableServletHttpRequest(DefaultMutableServletHttpRequest<B> view) {
        this(view.servletHttpRequest, view.conversionService, view::getParameters, view.headers);
        this.uri = view.uri;
        this.body = view.body;
        this.bodySet = view.bodySet;
        if (view.cookies != null) {
            this.cookies = new SimpleCookies(view.conversionService);
            view.cookies.getAll().forEach(cookie -> this.cookies.put(cookie.getName(), cookie));
        }
    }

    private DefaultMutableServletHttpRequest(DefaultServletHttpRequest<B> servletHttpRequest,
                                             ConversionService conversionService,
                                             Supplier<ConvertibleMultiValues<String>> parameters,
                                             ConvertibleMultiValues<String> headers) {
        this.servletHttpRequest = servletHttpRequest;
        this.conversionService = conversionService;
        this.parametersSource = parameters;
        SimpleHttpHeaders newHeaders = new SimpleHttpHeaders(new LinkedHashMap<>(), conversionService);
        newHeaders.setConversionService(conversionService);
        headers.forEach((name, values) -> {
            for (String value : values) {
                newHeaders.add(name, value);
            }
        });
        this.headers = newHeaders;
    }

    private static Map<CharSequence, List<String>> copyValues(ConvertibleMultiValues<String> params) {
        LinkedHashMap<CharSequence, List<String>> values = new LinkedHashMap<>(params.names().size());
        params.forEach(entry -> values.put(entry.getKey(), new ArrayList<>(entry.getValue())));
        return values;
    }

    ConversionService getConversionService() {
        return conversionService;
    }

    @Override
    public MutableHttpRequest<B> mutate() {
        return new DefaultMutableServletHttpRequest<>(this);
    }

    @Override
    public MutableHttpRequest<B> cookie(Cookie cookie) {
        SimpleCookies changed = cookies;
        if (changed == null) {
            changed = new SimpleCookies(conversionService);
            for (Cookie existing : servletHttpRequest.getCookies().getAll()) {
                changed.put(existing.getName(), existing);
            }
            cookies = changed;
        }
        changed.put(cookie.getName(), cookie);
        return this;
    }

    @Override
    public MutableHttpRequest<B> uri(URI uri) {
        // the query parameters follow the URI: those of the previous URI are replaced, the fields of a form kept.
        // Applied when the parameters are first read: the fields of a form may still be arriving
        Map<String, List<String>> previousQuery = new QueryStringDecoder(getUri()).parameters();
        Map<String, List<String>> newQuery = new QueryStringDecoder(uri).parameters();
        MutableHttpParameters current = parameters;
        Supplier<ConvertibleMultiValues<String>> base = current != null ? () -> current : parametersSource;
        this.parametersSource = () -> {
            Map<CharSequence, List<String>> values = new LinkedHashMap<>();
            newQuery.forEach((name, list) -> values.put(name, new ArrayList<>(list)));
            base.get().forEach(entry -> {
                List<String> query = previousQuery.getOrDefault(entry.getKey(), List.of());
                List<String> rest = new ArrayList<>(entry.getValue());
                query.forEach(rest::remove);
                if (!rest.isEmpty()) {
                    values.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(rest);
                }
            });
            return new SimpleHttpParameters(values, conversionService);
        };
        this.parameters = null;
        this.uri = uri;
        return this;
    }

    @Override
    public <T> MutableHttpRequest<T> body(@Nullable T body) {
        this.body = (B) body;
        this.bodySet = true;
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
        if (bodySet) {
            // the body the filter set, none if it cleared it
            return Optional.ofNullable(this.body);
        }
        return servletHttpRequest.getBody();
    }

    @Override
    public Cookies getCookies() {
        return cookies != null ? cookies : this.servletHttpRequest.getCookies();
    }

    @Override
    public MutableHttpParameters getParameters() {
        MutableHttpParameters current = parameters;
        if (current == null) {
            current = new SimpleHttpParameters(copyValues(parametersSource.get()), conversionService);
            current.setConversionService(conversionService);
            parameters = current;
        }
        return current;
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
    public InetSocketAddress getRemoteAddress() {
        return servletHttpRequest.getRemoteAddress();
    }

    @Override
    public InetSocketAddress getServerAddress() {
        return servletHttpRequest.getServerAddress();
    }

    @Override
    public @Nullable String getServerName() {
        return servletHttpRequest.getServerName();
    }

    @Override
    public boolean isSecure() {
        return servletHttpRequest.isSecure();
    }

    @Override
    public HttpVersion getHttpVersion() {
        return servletHttpRequest.getHttpVersion();
    }

    @Override
    public Optional<SSLSession> getSslSession() {
        return servletHttpRequest.getSslSession();
    }

    @Override
    public Optional<Certificate> getCertificate() {
        return servletHttpRequest.getCertificate();
    }

    @Override
    public Optional<Principal> getUserPrincipal() {
        return servletHttpRequest.getUserPrincipal();
    }

    @Override
    public Optional<Locale> getLocale() {
        return servletHttpRequest.getLocale();
    }

    @Override
    public String getContextPath() {
        return servletHttpRequest.getContextPath();
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

    @Override
    public ByteBody byteBody() {
        // the bytes of the request, which the route reads unless the body was set
        return servletHttpRequest.byteBody();
    }

    @Override
    public ByteBodyFactory byteBodyFactory() {
        return servletHttpRequest.byteBodyFactory();
    }

    @Override
    public @Nullable ByteBody byteBodyDirect() {
        // once the body was set, the bytes of the request are not its body
        return bodySet ? null : servletHttpRequest.byteBody();
    }

    @Override
    public boolean hasFormBody() {
        return !bodySet && servletHttpRequest.hasFormBody();
    }

    @Override
    public Publisher<RawFormField> getRawFormFields() {
        if (bodySet) {
            throw new IllegalStateException("The body of the request was set: it has no form fields to read");
        }
        return servletHttpRequest.getRawFormFields();
    }

    @Override
    public Publisher<RawFormField> getRawFormFields(ByteBody byteBody) {
        if (bodySet) {
            throw new IllegalStateException("The body of the request was set: it has no form fields to read");
        }
        return servletHttpRequest.getRawFormFields(byteBody);
    }

    @Override
    public void addDisposalResource(Runnable dispose) {
        servletHttpRequest.addDisposalResource(dispose);
    }
}
