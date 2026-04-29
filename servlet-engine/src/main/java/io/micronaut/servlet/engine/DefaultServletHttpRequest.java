/*
 * Copyright 2017-2026 original authors
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
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.core.util.SupplierUtil;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpParameters;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpVersion;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.ByteBufferBodyAdapter;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.stream.AvailableByteArrayBody;
import io.micronaut.http.body.stream.InputStreamByteBody;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.FormFieldMetadata;
import io.micronaut.http.multipart.RawFormField;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.servlet.http.BodyBuilder;
import io.micronaut.servlet.http.ParsedBodyHolder;
import io.micronaut.servlet.http.SSLSessionProvider;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.ServletHttpResponse;
import io.micronaut.servlet.http.StreamedServletMessage;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.MappingMatch;
import jakarta.servlet.http.Part;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Flux;

import javax.net.ssl.SSLSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Implementation of {@link HttpRequest} ontop of the Servlet API.
 *
 * @param <B> The body type
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public class DefaultServletHttpRequest<B> implements
    ServletHttpRequest<HttpServletRequest, B>,
    ServletExchange<HttpServletRequest, HttpServletResponse>,
    StreamedServletMessage<B, byte[]>,
    ServerHttpRequest<B>,
    ParsedBodyHolder<B>,
    FormCapableHttpRequest<B> {

    private static final String NULL_KEY = "Attribute key cannot be null";
    private static final String NULL_PARAMETER_NAME = "Parameter name cannot be null";

    private final ConversionService conversionService;
    private final HttpServletRequest delegate;
    private final URI uri;
    private final HttpMethod method;
    private final ServletRequestHeaders headers;
    private final ServletParameters parameters;
    private DefaultServletHttpResponse<B> primaryResponse;
    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;
    private final MutableConvertibleValues<Object> attributes;
    private volatile CloseableByteBody byteBody;
    private final ByteBodyFactory byteBodyFactory;
    private final Executor ioExecutor;
    private final SSLSessionProvider sslSessionProvider;
    private DefaultServletCookies cookies;
    private Supplier<Optional<B>> body;
    private List<Runnable> disposalResources;

    private boolean bodyIsReadAsync;
    private boolean bodyAccessed;
    private B parsedBody;
    private AsyncContext asyncContext;

    /**
     * Default constructor.
     *
     * @param conversionService  The servlet request
     * @param delegate           The servlet request
     * @param response           The servlet response
     * @param messageBodyHandlerRegistry      The message body handler registry
     * @param bodyBuilder        Body Builder
     * @param ioExecutor         Executor for blocking operations
     */
    private DefaultServletHttpRequest(ConversionService conversionService,
                                      HttpServletRequest delegate,
                                      HttpServletResponse response,
                                      MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                                      BodyBuilder bodyBuilder,
                                      Executor ioExecutor) {
        this(conversionService, delegate, response, messageBodyHandlerRegistry, bodyBuilder, ioExecutor, null);
    }

    /**
     * Default constructor.
     *
     * @param conversionService  The servlet request
     * @param delegate           The servlet request
     * @param response           The servlet response
     * @param messageBodyHandlerRegistry      The message body handler registry
     * @param bodyBuilder        Body Builder
     * @param ioExecutor         Executor for blocking operations
     * @param sslSessionProvider The {@link SSLSession} provider from attribute
     */
    protected DefaultServletHttpRequest(ConversionService conversionService,
                                        HttpServletRequest delegate,
                                        HttpServletResponse response,
                                        MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                                        BodyBuilder bodyBuilder,
                                        Executor ioExecutor,
                                        @Nullable SSLSessionProvider sslSessionProvider) {
        super();
        this.conversionService = conversionService;
        this.delegate = delegate;
        this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
        this.ioExecutor = ioExecutor;
        this.sslSessionProvider = sslSessionProvider;
        this.byteBodyFactory = ByteBodyFactory.createDefault(ByteArrayBufferFactory.INSTANCE);

        String requestURI = resolveRequestUri(delegate);

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
        this.primaryResponse = new DefaultServletHttpResponse<>(conversionService, this, response);
        this.body = SupplierUtil.memoizedNonEmpty(() -> {
            B built = parsedBody != null ? parsedBody : (B) bodyBuilder.buildBody(this::getInputStream, this);
            return Optional.ofNullable(built);
        });
        this.attributes = new MutableConvertibleValues<>() {

            @Override
            public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
                Objects.requireNonNull(conversionContext, "Conversion context cannot be null");
                Objects.requireNonNull(name, NULL_KEY);
                Object attribute = null;
                try {
                    attribute = delegate().getAttribute(name.toString());
                } catch (IllegalStateException e) {
                    // ignore, request not longer active
                }
                return Optional.ofNullable(attribute)
                        .flatMap(v -> conversionService.convert(v, conversionContext));
            }

            @Override
            public Set<String> names() {
                try {
                    Enumeration<String> attributeNames = delegate().getAttributeNames();
                    return CollectionUtils.enumerationToSet(attributeNames);
                } catch (IllegalStateException e) {
                    // ignore, request no longer active
                    return Set.of();
                }
            }

            @Override
            public Collection<Object> values() {
                try {
                    ServletRequest request = delegate();
                    return names().stream().map(request::getAttribute).toList();
                } catch (IllegalStateException e) {
                    // ignore, request no longer active
                    return Collections.emptyList();
                }
            }

            @Override
            public MutableConvertibleValues<Object> put(CharSequence key, @Nullable Object value) {
                Objects.requireNonNull(key, NULL_KEY);
                delegate().setAttribute(key.toString(), value);
                return this;
            }

            @Override
            public MutableConvertibleValues<Object> remove(CharSequence key) {
                Objects.requireNonNull(key, NULL_KEY);
                delegate().removeAttribute(key.toString());
                return this;
            }

            @Override
            public MutableConvertibleValues<Object> clear() {
                ServletRequest request = delegate();
                names().forEach(request::removeAttribute);
                return this;
            }
        };
    }

    private static String resolveRequestUri(HttpServletRequest delegate) {
        HttpServletMapping mapping = delegate.getHttpServletMapping();
        if (mapping == null || mapping.getMappingMatch() == null) {
            return delegate.getRequestURI();
        }
        String requestUri = delegate.getRequestURI();
        String contextPath = delegate.getContextPath();
        MappingMatch mappingMatch = mapping.getMappingMatch();
        return switch (mappingMatch) {
            case CONTEXT_ROOT, EXACT -> prependContextPath(contextPath, "/");
            case PATH -> stripServletPath(requestUri, contextPath, mapping.getPattern());
            case EXTENSION, DEFAULT -> requestUri;
        };
    }

    private static String prependContextPath(String contextPath, String path) {
        String normalizedPath = StringUtils.isNotEmpty(path) ? StringUtils.prependUri("/", path) : "/";
        return StringUtils.isNotEmpty(contextPath) ? contextPath + normalizedPath : normalizedPath;
    }

    private static String stripServletPath(String requestUri, String contextPath, String pattern) {
        String path = StringUtils.isNotEmpty(contextPath) && requestUri.startsWith(contextPath)
            ? requestUri.substring(contextPath.length())
            : requestUri;
        String servletPath = pattern.endsWith("/*") ? pattern.substring(0, pattern.length() - 2) : pattern;
        if (StringUtils.isEmpty(servletPath) || !path.startsWith(servletPath)) {
            return requestUri;
        }
        return prependContextPath(contextPath, path.substring(servletPath.length()));
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
     * @return The message body handler registry.
     */
    MessageBodyHandlerRegistry getMessageBodyHandlerRegistry() {
        return messageBodyHandlerRegistry;
    }

    @Override
    public boolean isAsyncSupported() {
        return asyncContext != null || delegate.isAsyncSupported();
    }

    @Override
    public void executeAsync(AsyncExecutionCallback asyncExecutionCallback) {
        if (asyncContext != null) {
            throw new IllegalStateException("Async execution has already been started");
        }
        this.asyncContext = delegate.startAsync();
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
        return delegate().isSecure();
    }

    @NonNull
    @Override
    public Optional<MediaType> getContentType() {
        String contentType = delegate().getContentType();
        return Optional.ofNullable(contentType)
            .map(MediaType::new);
    }

    @Override
    public long getContentLength() {
        return delegate().getContentLength();
    }

    @NonNull
    @Override
    public InetSocketAddress getRemoteAddress() {
        ServletRequest servletRequest = delegate();
        return new InetSocketAddress(
            servletRequest.getRemoteHost(),
            servletRequest.getRemotePort()
        );
    }

    protected final ServletRequest delegate() {
        return asyncContext != null ? asyncContext.getRequest() : delegate;
    }

    @NonNull
    @Override
    public InetSocketAddress getServerAddress() {
        return new InetSocketAddress(
           delegate().getLocalPort()
        );
    }

    @Nullable
    @Override
    public String getServerName() {
        return delegate().getServerName();
    }

    @Override
    @NonNull
    public Optional<Locale> getLocale() {
        return Optional.ofNullable(delegate().getLocale());
    }

    @NonNull
    @Override
    public Charset getCharacterEncoding() {
        String characterEncoding = delegate().getCharacterEncoding();
        return Optional.ofNullable(characterEncoding)
            .map(Charset::forName)
            .orElse(StandardCharsets.UTF_8);
    }

    @Override
    public String getContextPath() {
        return delegate.getContextPath();
    }

    @SuppressWarnings("resource")
    @Override
    public InputStream getInputStream() throws IOException {
        return byteBody().split(ByteBody.SplitBackpressureMode.FASTEST).toInputStream();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding()));
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
        return primaryResponse;
    }

    @Override
    public ServletHttpResponse<HttpServletResponse, ?> createResponse() {
        DefaultServletHttpResponse<B> r = (DefaultServletHttpResponse<B>) primaryResponse.createNewPrimaryResponse();
        primaryResponse = r;
        return r;
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
        byteBody().toByteArrayPublisher().subscribe(s);
    }

    @Override
    public @NonNull ByteBody byteBody() {
        bodyAccessed = true;
        CloseableByteBody body = byteBody;
        if (body == null) {
            synchronized (this) {
                body = byteBody;
                if (body == null) {
                    body = createByteBody();
                    byteBody = body;
                }
            }
        }
        return body;
    }

    @Override
    public ByteBodyFactory byteBodyFactory() {
        return byteBodyFactory;
    }

    @Override
    public void close() {
        runDisposalResources();
    }

    @Override
    public void prepareForResponse() {
        if (bodyAccessed || !hasFormBody()) {
            return;
        }
        bodyAccessed = prepareUnusedFormBodyForResponse();
    }

    @Override
    public Optional<SSLSession> getSslSession() {
        if (sslSessionProvider != null) {
            return sslSessionProvider.getSSLSession(this);
        }
        return ServletHttpRequest.super.getSslSession();
    }

    @Override
    public boolean hasFormBody() {
        return getContentType()
            .map(this::isFormContentType)
            .orElse(false);
    }

    @Override
    public Publisher<RawFormField> getRawFormFields() {
        MediaType mediaType = getContentType().orElse(null);
        if (mediaType == null || !isFormContentType(mediaType)) {
            throw new IllegalStateException("Not a form Content-Type. Please check hasFormBody() before calling this method.");
        }
        bodyAccessed = true;
        if (mediaType.matches(MediaType.MULTIPART_FORM_DATA_TYPE)) {
            return Flux.defer(() -> {
                try {
                    Collection<Part> parts = ((HttpServletRequest) delegate()).getParts();
                    discardByteBodyIfInitialized();
                    return Flux.fromIterable(parts)
                        .map(this::toRawFormFieldFromPart);
                } catch (IOException | ServletException e) {
                    return Flux.error(e);
                }
            });
        } else {
            return Flux.defer(() -> {
                var parameterMap = delegate().getParameterMap();
                discardByteBodyIfInitialized();
                return Flux.fromIterable(parameterMap.entrySet().stream()
                .flatMap(entry -> Arrays.stream(entry.getValue())
                    .map(value -> new RawFormField(new FormFieldMetadata(entry.getKey(), null, null), byteBodyFactory().adapt(value.getBytes(StandardCharsets.UTF_8)))))
                    .toList());
            });
        }
    }

    private void discardByteBodyIfInitialized() {
        CloseableByteBody body = byteBody;
        if (body != null) {
            body.allowDiscard();
        }
    }

    protected boolean prepareUnusedFormBodyForResponse() {
        return false;
    }

    private CloseableByteBody createByteBody() {
        long contentLengthLong = delegate.getContentLengthLong();
        OptionalLong length = contentLengthLong < 0 ? OptionalLong.empty() : OptionalLong.of(contentLengthLong);
        return InputStreamByteBody.create(new LazyDelegateInputStream(delegate), length, ioExecutor, byteBodyFactory);
    }

    @Override
    public synchronized void addDisposalResource(Runnable runnable) {
        Objects.requireNonNull(runnable, "Disposable resource cannot be null");
        if (disposalResources == null) {
            disposalResources = new ArrayList<>(2);
        }
        disposalResources.add(runnable);
    }

    private boolean isFormContentType(MediaType mediaType) {
        return mediaType.matches(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
            || mediaType.matches(MediaType.MULTIPART_FORM_DATA_TYPE);
    }

    private RawFormField toRawFormFieldFromPart(Part part) {
        try {
            CloseableByteBody partBody = InputStreamByteBody.create(part.getInputStream(), OptionalLong.of(part.getSize()), ioExecutor, byteBodyFactory);
            addDisposalResource(() -> safeDelete(part));
            FormFieldMetadata metadata = new FormFieldMetadata(
                part.getName(),
                part.getSubmittedFileName(),
                Optional.ofNullable(part.getContentType()).map(MediaType::new).orElse(null)
            );
            return new RawFormField(metadata, partBody);
        } catch (IOException e) {
            throw new InternalServerException("Error reading part [" + part.getName() + "]: " + e.getMessage(), e);
        }
    }

    private RawFormField toRawFormFieldFromValue(String name, String value, Charset charset) {
        byte[] bytes = value != null ? value.getBytes(charset) : ArrayUtils.EMPTY_BYTE_ARRAY;
        CloseableByteBody body = AvailableByteArrayBody.create(byteBodyFactory.readBufferFactory().adapt(bytes));
        FormFieldMetadata metadata = new FormFieldMetadata(name, null, MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        return new RawFormField(metadata, body);
    }

    private synchronized void runDisposalResources() {
        if (disposalResources == null || disposalResources.isEmpty()) {
            return;
        }
        List<Runnable> resources = disposalResources;
        disposalResources = null;
        for (Runnable runnable : resources) {
            runnable.run();
        }
    }

    @SuppressWarnings("java:S1166")
    private void safeDelete(Part part) {
        try {
            part.delete();
        } catch (Exception ignored) {
            // best-effort cleanup, never mask request processing failures
        }
    }

    /**
     * The servlet request headers.
     */
    private final class ServletRequestHeaders implements HttpHeaders {

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
    private final class ServletParameters implements HttpParameters {

        @Override
        public List<String> getAll(CharSequence name) {
            final String[] values = delegate.getParameterValues(
                Objects.requireNonNull(name, NULL_PARAMETER_NAME).toString()
            );
            if (values == null) {
                return Collections.emptyList();
            }
            return Arrays.asList(values);
        }

        @Nullable
        @Override
        public String get(CharSequence name) {
            return delegate.getParameter(
                Objects.requireNonNull(name, NULL_PARAMETER_NAME).toString()
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
            Class<?> rawType = argument.getType();
            final boolean isOptional = rawType == Optional.class;
            if (isOptional) {
                Optional<Argument<?>> firstTypeVariable = argument.getFirstTypeVariable();
                if (firstTypeVariable.isPresent()) {
                    rawType = firstTypeVariable.get().getType();
                }
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
