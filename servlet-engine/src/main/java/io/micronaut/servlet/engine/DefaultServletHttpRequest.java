/*
 * Copyright 2017-2021 original authors
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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpParameters;
import io.micronaut.http.MediaType;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.ServletHttpResponse;
import io.micronaut.servlet.http.StreamedServletMessage;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of {@link io.micronaut.http.HttpRequest} ontop of the Servlet API.
 *
 * @param <B> The body type
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public class DefaultServletHttpRequest<B> implements
    ServletHttpRequest<HttpServletRequest, B>,
    MutableConvertibleValues<Object>,
    ServletExchange<HttpServletRequest, HttpServletResponse>,
    StreamedServletMessage<B, byte[]> {

    private final ConversionService conversionService;
    private final HttpServletRequest delegate;
    private final URI uri;
    private final HttpMethod method;
    private final ServletRequestHeaders headers;
    private final ServletParameters parameters;
    private final DefaultServletHttpResponse<B> response;
    private final MediaTypeCodecRegistry codecRegistry;
    private DefaultServletCookies cookies;
    private Object body;
    private Scheduler scheduler;
    private boolean bodyIsReadAsync;

    /**
     * Default constructor.
     *
     * @param conversionService The servlet request
     * @param delegate          The servlet request
     * @param response          The servlet response
     * @param codecRegistry     The codec registry
     */
    protected DefaultServletHttpRequest(ConversionService conversionService,
                                        HttpServletRequest delegate,
                                        HttpServletResponse response,
                                        MediaTypeCodecRegistry codecRegistry) {
        this.conversionService = conversionService;
        this.delegate = delegate;
        this.codecRegistry = codecRegistry;
        final String contextPath = delegate.getContextPath();
        String requestURI = delegate.getRequestURI();
        if (StringUtils.isNotEmpty(contextPath) && requestURI.startsWith(contextPath)) {
            requestURI = requestURI.substring(contextPath.length());
        }

        String queryString = delegate.getQueryString();
        if (StringUtils.isNotEmpty(queryString)) {
            requestURI = requestURI + "?" + queryString;
        }

        this.uri = URI.create(requestURI);
        HttpMethod method;
        try {
            method = HttpMethod.valueOf(delegate.getMethod());
        } catch (IllegalArgumentException e) {
            method = HttpMethod.CUSTOM;
        }
        this.method = method;
        this.headers = new ServletRequestHeaders();
        this.parameters = new ServletParameters();
        this.response = new DefaultServletHttpResponse<>(conversionService, this, response);
    }

    /**
     * @return The codec registry.
     */
    public MediaTypeCodecRegistry getCodecRegistry() {
        return codecRegistry;
    }

    @Override
    public boolean isAsyncSupported() {
        return delegate.isAsyncSupported();
    }

    @Override
    public void executeAsync(AsyncExecutionCallback asyncExecutionCallback) {
        AsyncContext asyncContext = delegate.startAsync();
        asyncContext.start(() -> asyncExecutionCallback.run(asyncContext::complete));
    }

    @NonNull
    @Override
    public <T> Optional<T> getBody(@NonNull Argument<T> arg) {
        Objects.requireNonNull(arg);
        if (bodyIsReadAsync) {
            throw new IllegalStateException("Body is being read asynchronously!");
        }
        final Class<T> type = arg.getType();
        final MediaType contentType = getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
        long contentLength = getContentLength();
        if (body == null && contentLength != 0) {

            boolean isConvertibleValues = ConvertibleValues.class == type;
            if (isFormSubmission(contentType)) {
                body = getParameters();
                if (isConvertibleValues) {
                    return (Optional<T>) Optional.of(body);
                }
                return Optional.empty();
            }
            if (CharSequence.class.isAssignableFrom(type)) {
                try (BufferedReader reader = delegate.getReader()) {
                    final T value = (T) IOUtils.readText(reader);
                    body = value;
                    return Optional.ofNullable(value);
                } catch (IOException e) {
                    throw new CodecException("Error decoding request body: " + e.getMessage(), e);
                }
            }
            final MediaTypeCodec codec = codecRegistry.findCodec(contentType, type).orElse(null);
            if (codec != null) {
                try (InputStream inputStream = delegate.getInputStream()) {
                    if (isConvertibleValues) {
                        final Map map = codec.decode(Map.class, inputStream);
                        body = ConvertibleValues.of(map);
                        return (Optional<T>) Optional.of(body);
                    }
                    final T value = codec.decode(arg, inputStream);
                    body = value;
                    return Optional.ofNullable(value);
                } catch (CodecException | IOException e) {
                    throw new CodecException("Error decoding request body: " + e.getMessage(), e);
                }
            }
        } else {
            if (type.isInstance(body)) {
                return (Optional<T>) Optional.of(body);
            }
            if (body != null && body != parameters) {
                final T result = (T) conversionService.convertRequired(body, arg);
                return Optional.ofNullable(result);
            }

        }
        return Optional.empty();
    }

    @NonNull
    @Override
    public Optional<Principal> getUserPrincipal() {
        return Optional.ofNullable(
            ServletHttpRequest.super.getUserPrincipal()
                .orElse(delegate.getUserPrincipal())
        );
    }

    @Override
    public boolean isSecure() {
        return delegate.isSecure();
    }

    @NonNull
    @Override
    public Optional<MediaType> getContentType() {
        return Optional.ofNullable(delegate.getContentType())
            .map(MediaType::new);
    }

    @Override
    public long getContentLength() {
        return delegate.getContentLength();
    }

    @NonNull
    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress(
            delegate.getRemoteHost(),
            delegate.getRemotePort()
        );
    }

    @NonNull
    @Override
    public InetSocketAddress getServerAddress() {
        return new InetSocketAddress(
            delegate.getServerPort()
        );
    }

    @Nullable
    @Override
    public String getServerName() {
        return delegate.getServerName();
    }

    @Override
    @NonNull
    public Optional<Locale> getLocale() {
        return Optional.ofNullable(delegate.getLocale());
    }

    @NonNull
    @Override
    public Charset getCharacterEncoding() {
        return Optional.ofNullable(delegate.getCharacterEncoding())
            .map(Charset::forName)
            .orElse(StandardCharsets.UTF_8);
    }

    @Override
    public String getContextPath() {
        return delegate.getContextPath();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return delegate.getInputStream();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return delegate.getReader();
    }

    @Override
    public HttpServletRequest getNativeRequest() {
        return delegate;
    }

    @NonNull
    @Override
    public Cookies getCookies() {
        DefaultServletCookies cookies = this.cookies;
        if (cookies == null) {
            synchronized (this) { // double check
                cookies = this.cookies;
                if (cookies == null) {
                    cookies = new DefaultServletCookies(delegate.getCookies());
                    this.cookies = cookies;
                }
            }
        }
        return cookies;
    }

    @NonNull
    @Override
    public HttpParameters getParameters() {
        return parameters;
    }

    @NonNull
    @Override
    public HttpMethod getMethod() {
        return method;
    }

    @NonNull
    @Override
    public String getMethodName() {
        return Objects.requireNonNullElseGet(delegate.getMethod(), getMethod()::name);
    }

    @NonNull
    @Override
    public URI getUri() {
        return uri;
    }

    @NonNull
    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    @NonNull
    @Override
    public MutableConvertibleValues<Object> getAttributes() {
        return this;
    }

    @NonNull
    @Override
    public Optional<B> getBody() {
        return Optional.empty();
    }

    @Override
    public MutableConvertibleValues<Object> put(CharSequence key, @Nullable Object value) {
        String name = Objects.requireNonNull(key, "Key cannot be null").toString();
        if (value == null) {
            delegate.removeAttribute(name);
        } else {
            delegate.setAttribute(name, value);
        }
        return this;
    }

    @Override
    public MutableConvertibleValues<Object> remove(CharSequence key) {
        String name = Objects.requireNonNull(key, "Key cannot be null").toString();
        delegate.removeAttribute(name);
        return this;
    }

    @Override
    public MutableConvertibleValues<Object> clear() {
        while (delegate.getAttributeNames().hasMoreElements()) {
            String attr = delegate.getAttributeNames().nextElement();
            delegate.removeAttribute(attr);
        }
        return this;
    }

    @Override
    public Set<String> names() {
        return CollectionUtils.enumerationToSet(delegate.getAttributeNames());
    }

    @Override
    public Collection<Object> values() {
        return names()
            .stream()
            .map(delegate::getAttribute)
            .collect(Collectors.toList());
    }

    @Override
    public <T> Optional<T> get(CharSequence key, ArgumentConversionContext<T> conversionContext) {
        String name = Objects.requireNonNull(key, "Key cannot be null").toString();
        final Object v = delegate.getAttribute(name);
        if (v != null) {
            if (conversionContext.getArgument().getType().isInstance(v)) {
                //noinspection unchecked
                return (Optional<T>) Optional.of(v);
            } else {
                return conversionService.convert(v, conversionContext);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    @Override
    public ServletHttpRequest<HttpServletRequest, ? super Object> getRequest() {
        return (ServletHttpRequest) this;
    }

    @Override
    public ServletHttpResponse<HttpServletResponse, ?> getResponse() {
        return response;
    }

    private boolean isFormSubmission(MediaType contentType) {
        return MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(contentType) || MediaType.MULTIPART_FORM_DATA_TYPE.equals(contentType);
    }

    private <T> List<T> enumerationToList(Enumeration<T> enumeration) {
        List<T> set = new ArrayList<>(10);
        while (enumeration.hasMoreElements()) {
            set.add(enumeration.nextElement());
        }
        return set;
    }

    @Override
    public void subscribe(Subscriber<? super byte[]> s) {
        bodyIsReadAsync = true;
        Sinks.Many<byte[]> emitter = Sinks.many().replay().all();
        byte[] buffer = new byte[1024];
        try {
            ServletInputStream inputStream = delegate.getInputStream();
            inputStream.setReadListener(new ReadListener() {
                boolean complete = false;

                @Override
                public void onDataAvailable() {
                    if (!complete) {
                        try {
                            do {
                                int length = inputStream.read(buffer);
                                if (length == -1) {
                                    complete = true;
                                    emitter.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
                                    break;
                                } else {
                                    if (buffer.length == length) {
                                        emitter.emitNext(buffer, Sinks.EmitFailureHandler.FAIL_FAST);
                                    } else {
                                        emitter.emitNext(Arrays.copyOf(buffer, length), Sinks.EmitFailureHandler.FAIL_FAST);
                                    }
                                }
                            } while (inputStream.isReady());
                        } catch (IOException e) {
                            complete = true;
                            emitter.emitError(e, Sinks.EmitFailureHandler.FAIL_FAST);
                        }
                    }
                }

                @Override
                public void onAllDataRead() {
                    if (!complete) {
                        complete = true;
                        emitter.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    if (!complete) {
                        complete = true;
                        emitter.emitError(t, Sinks.EmitFailureHandler.FAIL_FAST);
                    }
                }
            });
        } catch (Exception e) {
            emitter.emitError(e, Sinks.EmitFailureHandler.FAIL_FAST);
        }
        Flux<byte[]> bodyContent = emitter.asFlux();
        bodyContent.subscribe(s);
    }

    /**
     * The servlet request headers.
     */
    private class ServletRequestHeaders implements HttpHeaders {

        @Override
        public List<String> getAll(CharSequence name) {
            final Enumeration<String> e =
                delegate.getHeaders(Objects.requireNonNull(name, "Header name should not be null").toString());

            return enumerationToList(e);
        }

        @Nullable
        @Override
        public String get(CharSequence name) {
            return delegate.getHeader(Objects.requireNonNull(name, "Header name should not be null").toString());
        }

        @Override
        public Set<String> names() {
            return CollectionUtils.enumerationToSet(delegate.getHeaderNames());
        }

        @Override
        public Collection<List<String>> values() {
            return names()
                .stream()
                .map(this::getAll)
                .collect(Collectors.toList());
        }

        @Override
        public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
            final String v = get(name);
            if (v != null) {
                return conversionService.convert(v, conversionContext);
            }
            return Optional.empty();
        }
    }

    /**
     * The servlet request parameters.
     */
    private class ServletParameters implements HttpParameters {

        @Override
        public List<String> getAll(CharSequence name) {
            final String[] values = delegate.getParameterValues(
                Objects.requireNonNull(name, "Parameter name cannot be null").toString()
            );
            return Arrays.asList(values);
        }

        @Nullable
        @Override
        public String get(CharSequence name) {
            return delegate.getParameter(
                Objects.requireNonNull(name, "Parameter name cannot be null").toString()
            );
        }

        @Override
        public Set<String> names() {
            return CollectionUtils.enumerationToSet(delegate.getParameterNames());
        }

        @Override
        public Collection<List<String>> values() {
            return names()
                .stream()
                .map(this::getAll)
                .collect(Collectors.toList());
        }

        @Override
        public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
            final Argument<T> argument = conversionContext.getArgument();
            Class rawType = argument.getType();
            final boolean isOptional = rawType == Optional.class;
            if (isOptional) {
                rawType = argument.getFirstTypeVariable().map(Argument::getType).orElse(rawType);
            }
            final boolean isIterable = Iterable.class.isAssignableFrom(rawType);
            final String paramName = Objects.requireNonNull(name, "Parameter name should not be null").toString();
            if (isIterable) {
                final String[] parameterValues = delegate.getParameterValues(paramName);
                if (ArrayUtils.isNotEmpty(parameterValues)) {
                    if (parameterValues.length == 1) {
                        return conversionService.convert(parameterValues[0], conversionContext);
                    } else {
                        if (isOptional) {
                            return (Optional<T>) conversionService.convert(parameterValues, ConversionContext.of(
                                argument.getFirstTypeVariable().orElse(argument)
                            ));
                        } else {
                            return conversionService.convert(parameterValues, conversionContext);
                        }
                    }
                } else {
                    return conversionService.convert(Collections.emptyList(), conversionContext);
                }
            } else {
                final String v = get(name);
                if (v != null) {
                    if (rawType.isInstance(v)) {
                        //noinspection unchecked
                        return (Optional<T>) Optional.of(v);
                    } else {
                        return conversionService.convert(v, conversionContext);
                    }
                }
            }
            return Optional.empty();
        }
    }
}
