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
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.core.util.SupplierUtil;
import io.micronaut.http.FullHttpRequest;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpParameters;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpVersion;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.servlet.http.BodyBuilder;
import io.micronaut.servlet.http.ByteArrayByteBuffer;
import io.micronaut.servlet.http.ParsedBodyHolder;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.ServletHttpResponse;
import io.micronaut.servlet.http.StreamedServletMessage;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Implementation of {@link HttpRequest} ontop of the Servlet API.
 *
 * @param <B> The body type
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public final class DefaultServletHttpRequest<B> implements
    ServletHttpRequest<HttpServletRequest, B>,
    ServletExchange<HttpServletRequest, HttpServletResponse>,
    StreamedServletMessage<B, byte[]>,
    FullHttpRequest<B>,
    ParsedBodyHolder<B> {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultServletHttpRequest.class);
    private static final String NULL_KEY = "Attribute key cannot be null";

    private final ConversionService conversionService;
    private final HttpServletRequest delegate;
    private final URI uri;
    private final HttpMethod method;
    private final ServletRequestHeaders headers;
    private final ServletParameters parameters;
    private final DefaultServletHttpResponse<B> response;
    private final MediaTypeCodecRegistry codecRegistry;
    private final MutableConvertibleValues<Object> attributes;
    private DefaultServletCookies cookies;
    private Supplier<Optional<B>> body;

    private boolean bodyIsReadAsync;
    private ByteArrayByteBuffer<B> servletByteBuffer;
    private B parsedBody;

    /**
     * Default constructor.
     *
     * @param conversionService The servlet request
     * @param delegate          The servlet request
     * @param response          The servlet response
     * @param codecRegistry     The codec registry
     * @param bodyBuilder       Body Builder
     */
    protected DefaultServletHttpRequest(ConversionService conversionService,
                                        HttpServletRequest delegate,
                                        HttpServletResponse response,
                                        MediaTypeCodecRegistry codecRegistry,
                                        BodyBuilder bodyBuilder) {
        super();
        this.conversionService = conversionService;
        this.delegate = delegate;
        this.codecRegistry = codecRegistry;

        String requestURI = delegate.getRequestURI();

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
        this.body = SupplierUtil.memoizedNonEmpty(() -> {
            B built = parsedBody != null ? parsedBody : (B) bodyBuilder.buildBody(this::getInputStream, this);
            return Optional.ofNullable(built);
        });
        this.attributes = new MutableConvertibleValues<>() {

            @Override
            public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
                Objects.requireNonNull(conversionContext, "Conversion context cannot be null");
                Objects.requireNonNull(name, NULL_KEY);
                Object attribute = delegate.getAttribute(name.toString());
                return Optional.ofNullable(attribute)
                        .flatMap(v -> conversionService.convert(v, conversionContext));
            }

            @Override
            public Set<String> names() {
                return CollectionUtils.enumerationToSet(delegate.getAttributeNames());
            }

            @Override
            public Collection<Object> values() {
                return names().stream().map(delegate::getAttribute).toList();
            }

            @Override
            public MutableConvertibleValues<Object> put(CharSequence key, @Nullable Object value) {
                Objects.requireNonNull(key, NULL_KEY);
                delegate.setAttribute(key.toString(), value);
                return this;
            }

            @Override
            public MutableConvertibleValues<Object> remove(CharSequence key) {
                Objects.requireNonNull(key, NULL_KEY);
                delegate.removeAttribute(key.toString());
                return this;
            }

            @Override
            public MutableConvertibleValues<Object> clear() {
                names().forEach(delegate::removeAttribute);
                return this;
            }
        };
    }

    /**
     * @return The conversion service.
     */
    public ConversionService getConversionService() {
        return conversionService;
    }

    @Override
    public HttpVersion getHttpVersion() {
        String protocol = getNativeRequest().getProtocol();
        return switch (protocol) {
            case "HTTP/2.0" -> HttpVersion.HTTP_2_0;
            default -> ServletHttpRequest.super.getHttpVersion();
        };
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
        if (bodyIsReadAsync) {
            throw new IllegalStateException("Body is being read asynchronously!");
        }

        return getBody().map(t -> conversionService.convertRequired(t, arg));
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
        return servletByteBuffer != null ? servletByteBuffer.toInputStream() : delegate.getInputStream();
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

    @Override
    public MutableHttpRequest<B> mutate() {
        return new DefaultMutableServletHttpRequest<>(this);
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
        return this.attributes;
    }

    @Override
    public void setParsedBody(B body) {
        this.parsedBody = body;
    }

    @NonNull
    @Override
    public Optional<B> getBody() {
        return this.body.get();
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
                                if (inputStream.isReady()) {

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

    @Override
    public boolean isFull() {
        return !bodyIsReadAsync;
    }

    @Override
    public ByteBuffer<?> contents() {
        if (bodyIsReadAsync) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Body is read asynchronously, cannot get contents");
            }
            return null;
        }
        try {
            if (servletByteBuffer == null) {
                this.servletByteBuffer = new ByteArrayByteBuffer<>(delegate.getInputStream().readAllBytes());
            }
            return servletByteBuffer;
        } catch (IOException e) {
            throw new IllegalStateException("Error getting all body contents", e);
        }
    }

    @Override
    public ExecutionFlow<ByteBuffer<?>> bufferContents() {
        ByteBuffer<?> contents = contents();
        if (contents == null) {
            return null;
        }
        return ExecutionFlow.just(contents);
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
                .toList();
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
                .toList();
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
