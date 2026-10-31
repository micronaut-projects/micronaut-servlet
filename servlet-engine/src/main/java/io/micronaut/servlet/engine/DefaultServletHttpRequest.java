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

import io.micronaut.servlet.http.AsyncRequestBodySourceHolder;
import io.micronaut.http.body.AsyncRequestBodySource;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.core.io.buffer.ReadBufferFactory;
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
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpParameters;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.body.AsyncRequestBody;
import io.micronaut.http.body.AvailableByteBody;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableAvailableByteBody;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.DirectByteBodyAccess;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.stream.AvailableByteArrayBody;
import io.micronaut.http.body.stream.BodySizeLimits;
import io.micronaut.http.body.stream.InputStreamByteBody;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.cookie.ServerCookieDecoder;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.FormFieldMetadata;
import io.micronaut.http.multipart.RawFormField;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.servlet.http.BodyBuilder;
import io.micronaut.servlet.http.LimitedInputStream;
import io.micronaut.servlet.http.BufferedFormDecoder;
import io.micronaut.servlet.http.ParsedBodyHolder;
import io.micronaut.servlet.http.SSLSessionProvider;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.ServletHttpResponse;
import io.micronaut.servlet.http.StreamedServletMessage;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.MethodBasedRouteInfo;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.net.ssl.SSLSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
    ServerHttpRequest<B>,
    ParsedBodyHolder<B>,
    FormCapableHttpRequest<B>,
    DirectByteBodyAccess,
    AsyncRequestBodySourceHolder {

    /**
     * How long the body the route did not read may take to arrive once the response is sent, before the connection
     * is closed instead, {@code micronaut.servlet.unread-body-drain-timeout} in milliseconds as a system property.
     */
    static volatile long drainTimeoutMillis = Long.getLong("micronaut.servlet.unread-body-drain-timeout", 10_000);

    private static final String NULL_KEY = "Attribute key cannot be null";
    private static final String NULL_PARAMETER_NAME = "Parameter name cannot be null";
    private static final Logger LOG = LoggerFactory.getLogger(DefaultServletHttpRequest.class);
    private final ConversionService conversionService;
    /**
     * Set by the handler when it receives the request, before the filters run: the hand-off of the request to
     * the threads of the filters publishes it.
     */
    private @Nullable AsyncRequestBodySource asyncRequestBodySource;
    /**
     * The ways the embedded server drops the connection of a response whose body failed, see
     * {@link ServletConnectionAborter}: none in a WAR deployment.
     */
    private final List<ServletConnectionAborter> aborters;
    private final HttpServletRequest delegate;
    private final URI uri;
    private final HttpMethod method;
    /**
     * The name of the method, read when the request is created: a container recycles its request once the
     * response completed, while e.g. an emitter that outlives it still logs the request.
     */
    private final String methodName;
    private final ServletRequestHeaders headers;
    private final ServletParameters parameters;
    private DefaultServletHttpResponse<B> primaryResponse;
    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;
    private final MutableConvertibleValues<Object> attributes;
    /**
     * The body, or {@code null} until a small declared body is read on first use, on the thread running the route.
     */
    private final AtomicReference<@Nullable CloseableByteBody> byteBody = new AtomicReference<>();
    /**
     * Set when the declared length exceeds {@code micronaut.server.max-request-size}: the body fails on every read,
     * and a form, whose fields the container parses from the same stream, is refused through the parameters too.
     */
    private @Nullable ContentLengthExceededException bodyTooLarge;
    private final long maxBodySize;
    private final long maxBufferSize;
    /**
     * The most fields a form may have, {@code micronaut.http.forms.max-decoded-key-value-parameters}.
     */
    private int maxFormFields = Integer.MAX_VALUE;
    /**
     * The most bytes of a file of a multipart form decoded from the byte body, {@code micronaut.server.multipart.max-file-size}.
     */
    private long maxFileSize = Long.MAX_VALUE;
    /**
     * Set when what is left of the body is known to exceed what is drained once the response is sent, e.g. a form
     * refused for its size: the connection is closed instead, see {@link #discardUnreadBody}.
     */
    private volatile boolean bodyBeyondDrainLimit;
    /**
     * The fields of a URL-encoded form that declared no length, read through the size limit before the container
     * could parse the same bytes unbounded; {@code null} until such a form is asked for, and for every other request.
     */
    private @Nullable Map<String, List<String>> unboundedForm;
    /**
     * A copy of the body of a URL-encoded form streamed through a {@code ReadListener}, which its fields are decoded
     * from, or {@code null}.
     */
    private @Nullable CloseableByteBody formCopy;
    private final ByteBodyFactory byteBodyFactory;
    private final Executor ioExecutor;
    private final @Nullable SSLSessionProvider sslSessionProvider;
    private @Nullable DefaultServletCookies cookies;
    private Supplier<Optional<B>> body;
    private final ConcurrentLinkedQueue<Runnable> disposalResources = new ConcurrentLinkedQueue<>();

    private boolean bodyIsReadAsync;
    /**
     * Whether the byte body opened the container's input stream: the container cannot parse the form any more
     * then, so the form is decoded from the byte body instead.
     */
    private volatile boolean bodyStreamOpened;
    /**
     * Whether the body is fed by a {@code ReadListener}, which the container only drives once the service
     * method has returned; such a request must leave the service thread before its body can be consumed.
     */
    private boolean bodyReadsAsynchronously;
    /**
     * Whether the body is a URL-encoded form small enough to be read inline: its fields are then decoded from the
     * byte body, so that the container's stream is read once, whether the form or the byte body is read first.
     */
    private boolean formReadsInline;
    /**
     * The publisher of a body read through a ReadListener, which drops what nobody read before the response
     * completes, or {@code null} if the body is not read that way.
     */
    private @Nullable ServletStreamPublisher streamPublisher;
    private @Nullable B parsedBody;
    private @Nullable AsyncContext asyncContext;

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
    DefaultServletHttpRequest(ConversionService conversionService,
                              HttpServletRequest delegate,
                              HttpServletResponse response,
                              MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                              BodyBuilder bodyBuilder,
                              Executor ioExecutor,
                              @Nullable SSLSessionProvider sslSessionProvider) {
        this(conversionService, delegate, response, messageBodyHandlerRegistry, bodyBuilder, ioExecutor, sslSessionProvider, BodySizeLimits.UNLIMITED);
    }

    /**
     * Constructor applying the server's body size limits.
     *
     * @param conversionService  The servlet request
     * @param delegate           The servlet request
     * @param response           The servlet response
     * @param messageBodyHandlerRegistry      The message body handler registry
     * @param bodyBuilder        Body Builder
     * @param ioExecutor         Executor for blocking operations
     * @param sslSessionProvider The {@link SSLSession} provider from attribute
     * @param bodySizeLimits     The limits from {@code micronaut.server.max-request-size} and
     *                           {@code micronaut.server.max-request-buffer-size}, enforced while the body is read
     * @since 6.2.0
     */
    @SuppressWarnings("java:S107") // the previous constructor's parameters plus the limits; package-private
    DefaultServletHttpRequest(ConversionService conversionService,
                              HttpServletRequest delegate,
                              HttpServletResponse response,
                              MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                              BodyBuilder bodyBuilder,
                              Executor ioExecutor,
                              @Nullable SSLSessionProvider sslSessionProvider,
                              BodySizeLimits bodySizeLimits) {
        this(conversionService, delegate, response, messageBodyHandlerRegistry, bodyBuilder, ioExecutor, sslSessionProvider, bodySizeLimits,
            List.of());
    }

    /**
     * Constructor of the requests of {@link DefaultServletHttpHandler}.
     *
     * @param conversionService  The servlet request
     * @param delegate           The servlet request
     * @param response           The servlet response
     * @param messageBodyHandlerRegistry      The message body handler registry
     * @param bodyBuilder        Body Builder
     * @param ioExecutor         Executor for blocking operations
     * @param sslSessionProvider The {@link SSLSession} provider from attribute
     * @param bodySizeLimits     The limits of the body
     * @param aborters           The {@link ServletConnectionAborter} beans
     */
    @SuppressWarnings("java:S107") // package-private
    DefaultServletHttpRequest(ConversionService conversionService,
                              HttpServletRequest delegate,
                              HttpServletResponse response,
                              MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                              BodyBuilder bodyBuilder,
                              Executor ioExecutor,
                              @Nullable SSLSessionProvider sslSessionProvider,
                              BodySizeLimits bodySizeLimits,
                              List<ServletConnectionAborter> aborters) {
        super();
        this.aborters = aborters;
        this.conversionService = conversionService;
        this.delegate = delegate;
        this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
        this.ioExecutor = ioExecutor;
        this.sslSessionProvider = sslSessionProvider;
        this.byteBodyFactory = ByteBodyFactory.createDefault(ByteArrayBufferFactory.INSTANCE);
        this.headers = new ServletRequestHeaders();
        this.maxBodySize = bodySizeLimits.maxBodySize();
        this.maxBufferSize = bodySizeLimits.maxBufferSize();
        this.byteBody.set(createBody(delegate.getContentLengthLong(), bodySizeLimits));

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
        this.methodName = Objects.requireNonNullElseGet(delegate.getMethod(), method::name);
        this.parameters = new ServletParameters();
        this.primaryResponse = new DefaultServletHttpResponse<>(conversionService, this, response);
        this.body = SupplierUtil.memoizedNonEmpty(() -> {
            if (parsedBody == null && readsBodyAsynchronously()) {
                // the route reads the body itself, through an AsyncRequestBody: like on the Netty server, the
                // request has no decoded body, and decoding it here would claim the bytes the route reads
                return Optional.empty();
            }
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
        AsyncContext startedAsyncContext = delegate.startAsync();
        this.asyncContext = startedAsyncContext;
        ContainerAsyncExecution execution = new ContainerAsyncExecution(startedAsyncContext);
        startedAsyncContext.addListener(execution);
        if (bodyReadsAsynchronously && mayHaveBody()) {
            // the body is read through a ReadListener, which the container only drives once the service
            // method has returned, so a request with a body has to leave this thread first
            startedAsyncContext.start(() -> asyncExecutionCallback.run(execution));
        } else {
            // otherwise the route runs on the thread already handling the request: AsyncContext.start(Runnable)
            // would hand it to another pool thread first, a hop that is pure overhead for the common GET, and
            // completing the context from the service thread is allowed by the specification
            asyncExecutionCallback.run(execution);
        }
    }

    /**
     * Chooses how the body is read, by the framing rules and the size limits.
     *
     * @return The body, or {@code null} for a small declared body that is read on first use, on the thread that
     * runs the route
     */
    // the body stream of a container without asynchronous reads is not closed with the body: what is left of it is
    // dropped once the response completed (see discardUnreadBody), and the container closes it with the exchange
    @SuppressWarnings("java:S2095")
    private @Nullable CloseableByteBody createBody(long contentLength, BodySizeLimits bodySizeLimits) {
        OptionalLong length = contentLength < 0 ? OptionalLong.empty() : OptionalLong.of(contentLength);
        if (contentLength == 0 || (contentLength < 0 && !mayHaveBody())) {
            // no body by the framing rules, so no stream, publisher or shared buffer is set up for it: most GET
            // requests take this path and it is otherwise the largest allocation of the request
            return byteBodyFactory.createEmpty();
        }
        if (contentLength > bodySizeLimits.maxBodySize()) {
            // refused without reading a byte: every read of the body fails, so a route that binds it answers
            // 413 as on the Netty server, while the route itself is still resolved for filters and security
            this.bodyTooLarge = new ContentLengthExceededException(bodySizeLimits.maxBodySize(), contentLength);
            return byteBodyFactory.adapt(Flux.error(bodyTooLarge), length);
        }
        if (readsInline(contentLength, bodySizeLimits, delegate)) {
            this.formReadsInline = isUrlEncodedForm();
            // a small body with a known length is read with a blocking read on first use, on the thread that runs
            // the route: that is what a blocking servlet application does, and it keeps the request on the
            // container thread instead of paying a dispatch and a ReadListener round trip (or, on a container
            // without asynchronous support, an IO executor hop) for a few bytes. It is deferred rather than read
            // here so that the request is already counted for graceful shutdown
            return null;
        }
        if (delegate.isAsyncSupported()) {
            // the shared streaming body applies the size limits itself
            ReadBufferFactory readBufferFactory = byteBodyFactory.readBufferFactory();
            this.bodyReadsAsynchronously = true;
            ServletStreamPublisher publisher = new ServletStreamPublisher(this::openBodyStream, bodySizeLimits.maxBodySize(), contentLength);
            this.streamPublisher = publisher;
            CloseableByteBody streamed = byteBodyFactory.adapt(
                Flux.from(publisher).map(readBufferFactory::adapt),
                bodySizeLimits,
                headers,
                null
            );
            if (isUrlEncodedForm()) {
                // the fields of the form are decoded from this copy, taken before anyone reads the body: a filter may
                // read a copy of the body first, and the container's stream is then consumed. Being the first copy,
                // it has the whole body by the time the copy of the filter does, so the parameters never wait on
                // the thread that delivers the body
                this.formCopy = streamed.split(ByteBody.SplitBackpressureMode.FASTEST);
            }
            return streamed;
        }
        // a container without asynchronous reads, e.g. the JDK server: the stream is read with blocking reads on the
        // IO executor, through the shared streaming body so that both size limits apply as on the other servers
        ReadBufferFactory readBufferFactory = byteBodyFactory.readBufferFactory();
        LazyDelegateInputStream stream = new LazyDelegateInputStream(this::openBodyStream);
        Flux<ByteBuffer> chunks = Flux.<ByteBuffer>generate(sink -> {
                try {
                    byte[] chunk = new byte[8192];
                    int n = stream.read(chunk);
                    if (n == -1) {
                        sink.complete();
                    } else {
                        sink.next(ByteBuffer.wrap(chunk, 0, n));
                    }
                } catch (IOException e) {
                    sink.error(e);
                }
            })
            // the stream is not closed when the body is cancelled: what is left of it is dropped once the response
            // completed, see discardUnreadBody, and the container closes it with the exchange
            .subscribeOn(Schedulers.fromExecutor(ioExecutor), false);
        return byteBodyFactory.adapt(chunks.map(readBufferFactory::adapt), bodySizeLimits, headers, null);
    }

    /**
     * Whether a body with the given declared length is read on the container thread before the route runs
     * rather than streamed through a {@code ReadListener}: it has to be non-empty, declare its length, fit the
     * buffer limit, and not be a multipart form, whose parts the container parses itself. A URL-encoded form is
     * read inline as well: its fields are decoded from the byte body, see {@link #readUnboundedForm()}.
     */
    private static boolean readsInline(long contentLength, BodySizeLimits bodySizeLimits) {
        return contentLength > 0 && contentLength <= bodySizeLimits.maxBufferSize();
    }

    private static boolean readsInline(long contentLength, BodySizeLimits bodySizeLimits, HttpServletRequest request) {
        if (!readsInline(contentLength, bodySizeLimits)) {
            return false;
        }
        String contentType = request.getContentType();
        return contentType == null || !MediaType.of(contentType).matches(MediaType.MULTIPART_FORM_DATA_TYPE);
    }

    /**
     * @return Whether the byte body opened the input stream of the container, which cannot parse the form then
     */
    @Internal
    public boolean isBodyStreamOpened() {
        return bodyStreamOpened;
    }

    private ServletInputStream openBodyStream() throws IOException {
        bodyStreamOpened = true;
        return delegate.getInputStream();
    }

    private static CloseableByteBody readInline(LazyDelegateInputStream.StreamOpener opener, ByteBodyFactory byteBodyFactory, long contentLength) {
        try (InputStream inputStream = opener.open()) {
            // only a body with a declared length that fits the buffer limit is read inline: reading that many bytes
            // fills an array of the exact size, where readAllBytes would fill a 16 KiB chunk and copy it
            return byteBodyFactory.adapt(inputStream.readNBytes((int) Math.min(contentLength, Integer.MAX_VALUE)));
        } catch (IOException e) {
            throw new InternalServerException("Error reading request body: " + e.getMessage(), e);
        }
    }

    /**
     * Whether the request may carry a body, by the framing rules: on HTTP/1 a body is announced by a
     * {@code Content-Length} or {@code Transfer-Encoding}; on HTTP/2 the headers cannot say, so a body is assumed.
     */
    private boolean mayHaveBody() {
        if (delegate.getContentLengthLong() > 0) {
            return true;
        }
        if (delegate.getContentLengthLong() == 0) {
            return false;
        }
        if (delegate.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            return true;
        }
        return getHttpVersion() != HttpVersion.HTTP_1_0 && getHttpVersion() != HttpVersion.HTTP_1_1;
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
        String contentType = contentTypeHeader();
        // MediaType.of returns the constant of a common type instead of parsing it again on every call
        return contentType == null ? Optional.empty() : Optional.of(MediaType.of(contentType));
    }

    @Override
    public long getContentLength() {
        return delegate().getContentLength();
    }

    @NonNull
    @Override
    public InetSocketAddress getRemoteAddress() {
        ServletRequest servletRequest = delegate();
        // the IP of the client, not its host name: a container that resolves the name, like the JDK server, has
        // it looked up again here, which costs a DNS query and need not give the address the client connected from
        return new InetSocketAddress(
            servletRequest.getRemoteAddr(),
            servletRequest.getRemotePort()
        );
    }

    /**
     * @return The Content-Type of the request: the container's, unless a filter changed the headers
     */
    private @Nullable String contentTypeHeader() {
        MutableHttpHeaders changed = headers.changed;
        return changed == null ? delegate.getContentType() : changed.get(HttpHeaders.CONTENT_TYPE);
    }

    /**
     * Sets the limits of the forms decoded by the engine itself rather than by the container.
     *
     * @param maxFormFields The most fields of a form
     * @param maxFileSize   The most bytes of a file of a multipart form
     */
    void setFormLimits(int maxFormFields, long maxFileSize) {
        this.maxFormFields = maxFormFields;
        this.maxFileSize = maxFileSize;
    }

    private ServletRequest delegate() {
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
    public synchronized Cookies getCookies() {
        if (headers.changed != null) {
            // e.g. a filter added a cookie to the headers: the container only has the cookies the client sent
            // decoded into servlet cookies, so that they behave like the ones of the container
            List<jakarta.servlet.http.Cookie> changed = new ArrayList<>();
            for (String header : headers.getAll(HttpHeaders.COOKIE)) {
                for (Cookie cookie : ServerCookieDecoder.INSTANCE.decode(header)) {
                    try {
                        changed.add(new jakarta.servlet.http.Cookie(cookie.getName(), cookie.getValue())); // NOSONAR a cookie the client sent, which carries no attributes
                    } catch (IllegalArgumentException e) {
                        // a name the Servlet API refuses, which the container would not offer either
                        LOG.debug("Ignoring the cookie [{}] of request [{} - {}]", cookie.getName(), getMethodName(), getUri(), e);
                    }
                }
            }
            return new DefaultServletCookies(changed.toArray(jakarta.servlet.http.Cookie[]::new));
        }
        DefaultServletCookies cookies = this.cookies;
        if (cookies == null) {
            cookies = new DefaultServletCookies(delegate.getCookies());
            this.cookies = cookies;
        }
        return cookies;
    }

    @NonNull
    @Override
    public MutableHttpParameters getParameters() {
        if (isFormSubmission()) {
            // form fields come from the container's parsing of the body, not from the byte body, so the size limit
            // has to be enforced here or an oversized form would be accepted whenever the container's own limit
            // is higher
            if (bodyTooLarge != null) {
                throw bodyTooLarge;
            }
            readUnboundedForm();
        }
        return parameters;
    }

    private boolean isFormSubmission() {
        String contentType = contentTypeHeader();
        return contentType != null && isFormContentType(MediaType.of(contentType));
    }

    private boolean isUrlEncodedForm() {
        String contentType = contentTypeHeader();
        return contentType != null && MediaType.of(contentType).matches(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
    }

    /**
     * Reads a URL-encoded form through the size limit, before the container parses it.
     *
     * <p>A declared length is checked against the limit up front, and a multipart body is bounded by the
     * {@code MultipartConfigElement} the container is given, but a chunked URL-encoded form would otherwise be
     * parsed by the container straight from the stream, subject only to its own limit. The fields are decoded
     * here instead, from bytes that passed through the limit, and the container contributes the query
     * parameters only, which is what it parses once the stream has been consumed. A form with a length is decoded
     * here too, in the order of its fields, and whatever its method: a container parses the form of a POST only.</p>
     */
    private synchronized void readUnboundedForm() {
        // the form is always decoded here: a container parses the form of a POST only, see the servlet
        // specification 3.1.1, and may not keep the order of the fields, which a form read part after part needs
        if (unboundedForm != null || !mayHaveBody() || !isUrlEncodedForm()) {
            return;
        }
        byte[] bytes;
        if (formReadsInline) {
            // a small form with a declared length is the byte body, read on the container thread: decoded from a copy
            // of it, the fields are the same whether the route, a filter or the parameters read it first
            try (CloseableAvailableByteBody copy = ((AvailableByteBody) byteBody()).split()) {
                bytes = copy.toByteArray();
            }
        } else if (formCopy != null) {
            // streamed through a ReadListener: decoded from the copy of the body taken for it, within the buffer
            // limit, whether or not a filter read the body first
            CloseableByteBody copy = formCopy;
            formCopy = null;
            bytes = buffer(copy);
        } else if (bodyStreamOpened) {
            // e.g. a filter read a copy of the body: the bytes went to the byte body, the container's stream is
            // consumed, so the form is decoded from a copy of the byte body, which the buffer limit bounds
            bytes = buffer(byteBody().split(ByteBody.SplitBackpressureMode.FASTEST));
        } else {
            // the whole form is held in memory, so it is bounded by the buffer limit. The container's stream is not
            // closed: a container like Undertow would read the rest of the body to its end to close it
            try {
                bytes = readLimited(delegate.getInputStream(), Math.min(maxBodySize, maxBufferSize));
            } catch (ContentLengthExceededException e) {
                // the rest of the body is not drained, the connection is closed instead
                bodyBeyondDrainLimit = true;
                throw e;
            } catch (IOException e) {
                throw new InternalServerException("Error reading request body: " + e.getMessage(), e);
            }
        }
        Map<String, List<String>> fields = new LinkedHashMap<>();
        delegate.getParameterMap().forEach((name, values) -> fields.put(name, new ArrayList<>(Arrays.asList(values))));
        Charset charset = getCharacterEncoding();
        int count = 0;
        for (String pair : new String(bytes, charset).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            if (++count > maxFormFields) {
                throw BufferedFormDecoder.tooManyFields(maxFormFields);
            }
            int equals = pair.indexOf('=');
            String name = BufferedFormDecoder.decodeComponent(equals == -1 ? pair : pair.substring(0, equals), charset);
            String value = equals == -1 ? "" : BufferedFormDecoder.decodeComponent(pair.substring(equals + 1), charset);
            fields.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
        }
        this.unboundedForm = fields;
    }

    // the stream of the container is not closed, see readUnboundedForm
    @SuppressWarnings("java:S2095")
    private static byte[] readLimited(InputStream stream, long limit) throws IOException {
        return new LimitedInputStream(stream, limit).readAllBytes();
    }

    /**
     * @param copy A copy of the byte body, which this closes
     * @return Its bytes, within the buffer limit
     */
    private static byte[] buffer(CloseableByteBody copy) {
        try (copy; CloseableAvailableByteBody buffered = copy.buffer().toCompletableFuture().join()) {
            return buffered.toByteArray();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new InternalServerException("Error reading request body: " + e.getCause().getMessage(), e.getCause());
        }
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
        return methodName;
    }

    @NonNull
    @Override
    public URI getUri() {
        return uri;
    }

    @NonNull
    @Override
    public MutableHttpHeaders getHeaders() {
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

    /**
     * Whether the matched route takes the body as an {@link AsyncRequestBody}, as a handler route that declares
     * a body does, which the route tells since it was built.
     */
    private boolean readsBodyAsynchronously() {
        return RouteAttributes.getRouteInfo(this)
            .filter(MethodBasedRouteInfo.class::isInstance)
            .map(route -> ((MethodBasedRouteInfo<?, ?>) route).isAsyncRequestBody())
            .orElse(false);
    }


    /**
     * @param request A request a route is bound from
     * @return The exchange of the servlet request behind it, which is the request itself or, for the mutable view
     * a filter continued with, the request it is a view of
     */
    @Internal
    public static @Nullable ServletExchange<?, ?> exchangeOf(HttpRequest<?> request) {
        if (request instanceof ServletExchange<?, ?> exchange) {
            return exchange;
        }
        if (request instanceof DefaultMutableServletHttpRequest<?> view) {
            return view.servletRequest();
        }
        return null;
    }

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
        CloseableByteBody current = byteBody.get();
        if (current == null) {
            synchronized (this) {
                current = byteBody.get();
                if (current == null) {
                    current = readInline(this::openBodyStream, byteBodyFactory, delegate.getContentLengthLong());
                    byteBody.set(current);
                }
            }
        }
        return current;
    }

    @Override
    public ByteBody byteBodyDirect() {
        return byteBody();
    }

    @Override
    public ByteBodyFactory byteBodyFactory() {
        return byteBodyFactory;
    }

    @Override
    public void close() {
        CloseableByteBody copy = formCopy;
        if (copy != null) {
            formCopy = null;
            copy.close();
        }
        CloseableByteBody current = byteBody.get();
        if (current != null) {
            current.close();
        }
        runDisposalResources();
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
            .map(DefaultServletHttpRequest::isFormContentType)
            .orElse(false);
    }

    @Override
    public Publisher<RawFormField> getRawFormFields() {
        MediaType mediaType = getContentType().orElse(null);
        if (mediaType == null || !isFormContentType(mediaType)) {
            throw new IllegalStateException("Not a form Content-Type. Please check hasFormBody() before calling this method.");
        }
        if (bodyStreamOpened) {
            // e.g. a filter read a copy of the body: the bytes went to the byte body, not to the container
            return getRawFormFields(byteBody().split(ByteBody.SplitBackpressureMode.FASTEST));
        }
        if (mediaType.matches(MediaType.MULTIPART_FORM_DATA_TYPE)) {
            return Flux.defer(() -> {
                try {
                    Collection<Part> parts = ServletParts.parts((HttpServletRequest) delegate());
                    return Flux.fromIterable(parts)
                        .map(this::toRawFormFieldFromPart);
                } catch (IOException | ServletException e) {
                    return Flux.error(e);
                }
            });
        } else {
            HttpParameters formParameters = getParameters();
            return Flux.fromIterable(formParameters.names().stream()
                .flatMap(name -> formParameters.getAll(name).stream()
                    .map(value -> new RawFormField(new FormFieldMetadata(name, null, null), byteBodyFactory().adapt(value.getBytes(StandardCharsets.UTF_8)))))
                .toList());
        }
    }

    @Override
    public Publisher<RawFormField> getRawFormFields(ByteBody byteBody) {
        MediaType mediaType = getContentType().orElse(null);
        if (mediaType == null || !isFormContentType(mediaType)) {
            if (byteBody instanceof CloseableByteBody closeable) {
                // the publisher owns the bytes
                closeable.close();
            }
            throw new IllegalStateException("Not a form Content-Type. Please check hasFormBody() before calling this method.");
        }
        // e.g. a copy of the body that a filter reads: the container parses only the bytes it received, so
        // the copy is buffered, within the limits of the copy, and decoded here
        Charset charset = getCharacterEncoding();
        if (byteBody instanceof CloseableAvailableByteBody available) {
            // e.g. a form read inline: decoded right away, on this thread
            byte[] bytes;
            try (available) {
                bytes = available.toByteArray();
            }
            return Flux.fromIterable(BufferedFormDecoder.decode(mediaType, bytes, charset, maxFormFields, maxFileSize))
                .map(field -> new RawFormField(field.metadata(), AvailableByteArrayBody.create(byteBodyFactory.readBufferFactory().adapt(field.content()))));
        }
        return Mono.fromCompletionStage(() -> byteBody.buffer())
            .flatMapMany(buffered -> {
                byte[] bytes;
                try (buffered) {
                    bytes = buffered.toByteArray();
                }
                return Flux.fromIterable(BufferedFormDecoder.decode(mediaType, bytes, charset, maxFormFields, maxFileSize))
                    .map(field -> new RawFormField(field.metadata(), AvailableByteArrayBody.create(byteBodyFactory.readBufferFactory().adapt(field.content()))));
            })
            .doOnDiscard(RawFormField.class, RawFormField::close);
    }

    @Override
    public boolean abortResponse(Throwable failure) {
        for (ServletConnectionAborter aborter : aborters) {
            try {
                if (aborter.abort(delegate, primaryResponse.getNativeResponse(), failure)) {
                    return true;
                }
            } catch (RuntimeException e) {
                LOG.debug("Failed to drop the connection of request [{} - {}]", getMethodName(), getUri(), e);
            }
        }
        return false;
    }

    @Override
    public void discardUnreadBody(Runnable then) {
        ServletStreamPublisher publisher = streamPublisher;
        if (publisher == null && (!mayHaveBody() || (bodyStreamOpened && delegate.isAsyncSupported()))) {
            // no body, or one read inline: the container drops what is left of a small body itself
            then.run();
            return;
        }
        if (bodyTooLarge != null || bodyBeyondDrainLimit) {
            // what is left is known to exceed the drain limit: the client is told the connection closes, and it is
            // closed once the response went out, rather than read to its end
            closeConnection(bodyTooLarge != null ? bodyTooLarge : new ContentLengthExceededException(maxBodySize, maxBodySize + 1));
            then.run();
            return;
        }
        if (publisher != null) {
            discardAsync(publisher, then);
        } else if (delegate.isAsyncSupported()) {
            discardInlineBody(then);
        } else {
            discardBlocking(then);
        }
    }

    /**
     * Drops a body with a declared length within the buffer limit that was to be read inline and that nobody read:
     * what has arrived is dropped on this thread, and the rest, if any, once the response is sent, on the IO
     * executor rather than on this thread, which may be the one completing the response, within the drain timeout.
     * Its stream is never closed: a container like Undertow would read the rest of the body to its end to close it.
     */
    private void discardInlineBody(Runnable then) {
        ServletInputStream in;
        long remaining = delegate.getContentLengthLong();
        try {
            in = delegate.getInputStream();
            while (remaining > 0 && in.available() > 0) {
                long skipped = in.skip(Math.min(remaining, in.available()));
                if (skipped <= 0) {
                    break;
                }
                remaining -= skipped;
            }
            if (remaining <= 0 || in.isFinished()) {
                then.run();
                return;
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("Failed to drop the unread body of request [{} - {}]", getMethodName(), getUri(), e);
            then.run();
            return;
        }
        // something is left: the response goes out first, so that draining the body does not delay it
        commitResponse();
        AtomicBoolean done = new AtomicBoolean();
        Disposable timeout = Mono.delay(Duration.ofMillis(drainTimeoutMillis)).subscribe(ignored -> {
            if (done.compareAndSet(false, true)) {
                // closing the connection also ends the read blocked on the IO executor
                closeConnection(new TimeoutException("The unread body of the request was not received within " + drainTimeoutMillis + "ms"));
                then.run();
            }
        });
        long left = remaining;
        ioExecutor.execute(() -> {
            boolean complete = drop(in, left);
            if (done.compareAndSet(false, true)) {
                timeout.dispose();
                if (!complete) {
                    closeConnection(new ContentLengthExceededException(maxBodySize, maxBodySize + 1));
                }
                then.run();
            }
        });
    }

    /**
     * @return Whether the body was read to its end, or failed; {@code false} if it has more than it declared
     */
    private boolean drop(InputStream in, long remaining) {
        try {
            byte[] buffer = new byte[(int) Math.min(8192, Math.max(1, remaining))];
            long left = remaining;
            int n;
            while ((n = in.read(buffer)) != -1) {
                left -= n;
                if (left < 0) {
                    return false;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("Failed to drop the unread body of request [{} - {}]", getMethodName(), getUri(), e);
        }
        return true;
    }

    /**
     * Drops the rest of the body through the publisher's {@code ReadListener}, within the size limit and the drain
     * timeout: past either, the connection is closed instead.
     */
    private void discardAsync(ServletStreamPublisher publisher, Runnable then) {
        // done once the body was dropped, the drain failed or timed out; the lock keeps the response from being
        // committed below while it completes
        AtomicBoolean done = new AtomicBoolean();
        Object lock = new Object();
        AtomicReference<@Nullable Disposable> timeout = new AtomicReference<>();
        try {
            publisher.discard(maxBodySize, () -> {
                synchronized (lock) {
                    if (!done.compareAndSet(false, true)) {
                        return;
                    }
                }
                Disposable timer = timeout.get();
                if (timer != null) {
                    timer.dispose();
                }
                if (publisher.isDiscardIncomplete()) {
                    closeConnection(new ContentLengthExceededException(maxBodySize, maxBodySize + 1));
                }
                then.run();
            });
        } catch (RuntimeException e) {
            // the drain never stops the response from completing
            LOG.debug("Failed to drop the unread body of request [{} - {}]", getMethodName(), getUri(), e);
            if (done.compareAndSet(false, true)) {
                then.run();
            }
            return;
        }
        synchronized (lock) {
            if (done.get()) {
                // e.g. the route read the whole body: the response completes as usual
                return;
            }
            // something is left: the response goes out first, so that draining the body does not delay it
            commitResponse();
        }
        timeout.set(Mono.delay(Duration.ofMillis(drainTimeoutMillis)).subscribe(ignored -> {
            synchronized (lock) {
                if (!done.compareAndSet(false, true)) {
                    return;
                }
            }
            closeConnection(new TimeoutException("The unread body of the request was not received within " + drainTimeoutMillis + "ms"));
            then.run();
        }));
        if (done.get()) {
            // dropped meanwhile, before the timer was set
            Disposable timer = timeout.get();
            if (timer != null) {
                timer.dispose();
            }
        }
    }

    /**
     * Drops the rest of the body with blocking reads, on a container without asynchronous reads, e.g. the JDK
     * server, within the size limit and the drain timeout: past either, the container closes the stream itself.
     */
    private void discardBlocking(Runnable then) {
        // the body is read on this thread, the container's, as the container itself would before reusing the
        // connection: a server like the JDK one otherwise drops the connection while the client is still sending it
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainTimeoutMillis);
        try (InputStream in = delegate.getInputStream()) {
            // a body read inline is already at its end: the buffer is only allocated when something is left
            if (in.read() != -1) {
                // something is left: the response goes out first, so that draining the body does not delay it
                commitResponse();
                byte[] buffer = new byte[8192];
                long dropped = 1;
                int n;
                while (dropped <= maxBodySize && System.nanoTime() < deadline && (n = in.read(buffer)) != -1) {
                    dropped += n;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("Failed to drop the unread body of request [{} - {}]", getMethodName(), getUri(), e);
        }
        then.run();
    }

    /**
     * Sends what the response has buffered, which commits it, before the unread body is drained.
     */
    private void commitResponse() {
        try {
            HttpServletResponse response = primaryResponse.getNativeResponse();
            if (!response.isCommitted()) {
                response.flushBuffer();
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("Failed to send the response of request [{} - {}]", getMethodName(), getUri(), e);
        }
    }

    /**
     * Closes the connection once the response is sent, rather than draining the rest of the body: an HTTP/1
     * response that is not committed yet tells the client so with {@code Connection: close}, so that it stops
     * sending, and the embedded server drops the connection, see {@link ServletConnectionAborter}.
     */
    private void closeConnection(Throwable reason) {
        try {
            HttpServletResponse response = primaryResponse.getNativeResponse();
            if (!response.isCommitted() && (getHttpVersion() == HttpVersion.HTTP_1_0 || getHttpVersion() == HttpVersion.HTTP_1_1)) {
                response.setHeader(HttpHeaders.CONNECTION, "close");
            }
        } catch (RuntimeException e) {
            LOG.debug("Failed to mark the response of request [{} - {}] to close the connection", getMethodName(), getUri(), e);
        }
        commitResponse();
        abortResponse(reason);
    }

    @Override
    public synchronized void addDisposalResource(Runnable runnable) {
        Objects.requireNonNull(runnable, "Disposable resource cannot be null");
        disposalResources.add(runnable);
    }

    private static boolean isFormContentType(MediaType mediaType) {
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

    private synchronized void runDisposalResources() {
        Runnable runnable;
        while ((runnable = disposalResources.poll()) != null) {
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

    @Override
    public void setAsyncRequestBodySource(@Nullable AsyncRequestBodySource source) {
        this.asyncRequestBodySource = source;
    }

    @Override
    public @Nullable AsyncRequestBody asyncRequestBody(HttpRequest<?> request) {
        AsyncRequestBodySource source = asyncRequestBodySource;
        return source == null ? null : source.asyncRequestBody(request);
    }

    /**
     * The servlet request headers.
     */
    private final class ServletRequestHeaders implements MutableHttpHeaders {

        /**
         * The headers once they were changed, e.g. through a mutable view of a request a filter wrapped:
         * the container's are read-only, so they are copied on the first change.
         */
        private @Nullable MutableHttpHeaders changed;

        private MutableHttpHeaders changed() {
            MutableHttpHeaders copy = changed;
            if (copy == null) {
                SimpleHttpHeaders headers = new SimpleHttpHeaders(new LinkedHashMap<>(), conversionService);
                for (String name : names()) {
                    for (String value : getAll(name)) {
                        headers.add(name, value);
                    }
                }
                copy = headers;
                changed = copy;
            }
            return copy;
        }

        @Override
        public List<String> getAll(CharSequence name) {
            MutableHttpHeaders copy = changed;
            if (copy != null) {
                return copy.getAll(name);
            }
            final Enumeration<String> e =
                delegate.getHeaders(Objects.requireNonNull(name, "Header name should not be null").toString());

            return enumerationToList(e);
        }

        @Nullable
        @Override
        public String get(CharSequence name) {
            MutableHttpHeaders copy = changed;
            if (copy != null) {
                return copy.get(name);
            }
            return delegate.getHeader(Objects.requireNonNull(name, "Header name should not be null").toString());
        }

        @Override
        public Set<String> names() {
            MutableHttpHeaders copy = changed;
            if (copy != null) {
                return copy.names();
            }
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

        @Override
        public MutableHttpHeaders add(CharSequence header, CharSequence value) {
            changed().add(header, value);
            return this;
        }

        @Override
        public MutableHttpHeaders remove(CharSequence header) {
            changed().remove(header);
            return this;
        }

        @Override
        public void setConversionService(ConversionService conversionService) {
            // the conversions of the request
        }
    }

    /**
     * The asynchronous execution, which also hears from the container when it ends the execution itself.
     *
     * <p>A container times an asynchronous request out (30 seconds by default on Jetty, Tomcat and Undertow)
     * or errors it when the connection fails, and without a listener it then completes the context on its own.
     * The handler's completion callback would never run, so the request stayed counted as active, holding a
     * graceful shutdown up for the whole grace period, and its resources were never released. The listener hands
     * such an ending to the hook the handler registers; the container's own completion then follows.</p>
     */
    private static final class ContainerAsyncExecution implements AsyncExecution, AsyncListener {

        private final AsyncContext asyncContext;
        private final AtomicReference<@Nullable Runnable> endedByContainer = new AtomicReference<>();
        private volatile boolean ended;

        ContainerAsyncExecution(AsyncContext asyncContext) {
            this.asyncContext = asyncContext;
        }

        @Override
        public void complete() {
            asyncContext.complete();
        }

        @Override
        public void onEndedByContainer(Runnable hook) {
            endedByContainer.set(hook);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            // the container would answer 500 for a timeout it completes itself; say the same when the response
            // has not gone out yet, rather than letting an untouched 200 through
            if (event.getSuppliedResponse() instanceof HttpServletResponse response && !response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            ended();
        }

        @Override
        public void onError(AsyncEvent event) {
            ended();
        }

        @Override
        public void onComplete(AsyncEvent event) {
            // the execution completed through complete(), or after the hook has run
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // a re-dispatch is not something this engine does
        }

        @Override
        public boolean isEndedByContainer() {
            return ended;
        }

        private void ended() {
            ended = true;
            Runnable hook = endedByContainer.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
        }
    }

    /**
     * The servlet request parameters.
     */
    private final class ServletParameters implements MutableHttpParameters {

        /**
         * The parameters once they were changed, e.g. through a mutable view of a request a filter
         * wrapped: the container's are read-only, so they are copied on the first change.
         */
        private @Nullable Map<String, List<String>> changed;

        @Override
        public MutableHttpParameters add(CharSequence name, List<CharSequence> values) {
            Map<String, List<String>> copy = changed;
            if (copy == null) {
                copy = new LinkedHashMap<>();
                for (String n : names()) {
                    copy.put(n, new ArrayList<>(getAll(n)));
                }
                changed = copy;
            }
            List<String> list = copy.computeIfAbsent(Objects.requireNonNull(name, NULL_PARAMETER_NAME).toString(), k -> new ArrayList<>(values.size()));
            for (CharSequence value : values) {
                list.add(value.toString());
            }
            return this;
        }

        @Override
        public void setConversionService(ConversionService conversionService) {
            // the conversions of the request
        }

        private @Nullable String[] values(String name) {
            Map<String, List<String>> copy = changed;
            if (copy != null) {
                List<String> values = copy.get(name);
                return values == null ? null : values.toArray(String[]::new);
            }
            Map<String, List<String>> form = unboundedForm;
            if (form != null) {
                List<String> values = form.get(name);
                return values == null ? null : values.toArray(String[]::new);
            }
            return delegate.getParameterValues(name);
        }

        @Override
        public List<String> getAll(CharSequence name) {
            final String[] values = values(Objects.requireNonNull(name, NULL_PARAMETER_NAME).toString());
            if (values == null) {
                return Collections.emptyList();
            }
            return Arrays.asList(values);
        }

        @Nullable
        @Override
        public String get(CharSequence name) {
            final String[] values = values(Objects.requireNonNull(name, NULL_PARAMETER_NAME).toString());
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public Set<String> names() {
            Map<String, List<String>> copy = changed;
            if (copy != null) {
                return copy.keySet();
            }
            Map<String, List<String>> form = unboundedForm;
            if (form != null) {
                return form.keySet();
            }
            // in the order of the request: a form is read part after part
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> parameterNames = delegate.getParameterNames();
            while (parameterNames.hasMoreElements()) {
                names.add(parameterNames.nextElement());
            }
            return names;
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
                final String[] parameterValues = values(paramName);
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
