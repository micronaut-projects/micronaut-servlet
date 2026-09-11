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
package io.micronaut.servlet.http;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.LifeCycle;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.async.subscriber.LazySendingSubscriber;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.core.io.buffer.ReadBuffer;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.util.SupplierUtil;
import io.micronaut.http.ByteBodyHttpResponse;
import io.micronaut.http.ByteBodyHttpResponseWrapper;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.body.AvailableByteBody;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.stream.BodySizeLimits;
import io.micronaut.http.context.ServerHttpRequestContext;
import io.micronaut.http.context.event.HttpRequestReceivedEvent;
import io.micronaut.http.context.event.HttpRequestTerminatedEvent;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.RequestLifecycle;
import io.micronaut.http.server.ResponseLifecycle;
import io.micronaut.http.server.RouteExecutor;
import io.micronaut.http.server.types.files.FileCustomizableResponseType;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.http.server.types.files.SystemFile;
import io.micronaut.servlet.http.websocket.ServletWebSocketUpgrader;
import io.micronaut.servlet.http.websocket.WebSocketUpgradeRequestLifecycle;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.resource.StaticResourceResolver;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * An HTTP handler that can deal with Serverless requests.
 *
 * @param <REQ> The request object
 * @param <RES> The response object
 * @author graemerocher
 * @since 1.2.0
 */
public abstract class ServletHttpHandler<REQ, RES> implements AutoCloseable, LifeCycle<ServletHttpHandler<REQ, RES>> {
    /**
     * Logger to be used by subclasses for logging.
     */
    protected static final Logger LOG = LoggerFactory.getLogger(ServletHttpHandler.class);

    protected final ApplicationContext applicationContext;
    private final RouteExecutor routeExecutor;
    private final ConversionService conversionService;
    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;
    private final StaticResourceResolver staticResourceResolver;
    private final Supplier<Executor> ioExecutor;
    private final Supplier<ServletWebSocketUpgrader> webSocketUpgrader;
    private final Supplier<Router> router;

    /**
     * Requests received and not yet terminated. Drives graceful shutdown: the server stops accepting, then waits for
     * this to reach zero.
     */
    private final AtomicLong activeRequests = new AtomicLong();

    /**
     * Completed once a drain has been requested and the last active request has terminated.
     */
    private final AtomicReference<CompletableFuture<Void>> drained = new AtomicReference<>();

    /**
     * Default constructor.
     *
     * @param applicationContext The application context
     * @param conversionService  The conversion service
     */
    protected ServletHttpHandler(ApplicationContext applicationContext, ConversionService conversionService) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "The application context cannot be null");
        this.messageBodyHandlerRegistry = applicationContext.getBean(MessageBodyHandlerRegistry.class);
        this.staticResourceResolver = applicationContext.getBean(StaticResourceResolver.class);
        this.routeExecutor = applicationContext.getBean(RouteExecutor.class);
        this.conversionService = conversionService;
        this.ioExecutor = SupplierUtil.memoized(() -> applicationContext.getBean(Executor.class, Qualifiers.byName(TaskExecutors.BLOCKING)));
        this.webSocketUpgrader = SupplierUtil.memoized(() -> applicationContext.findBean(ServletWebSocketUpgrader.class).orElse(null));
        this.router = SupplierUtil.memoized(() -> applicationContext.getBean(Router.class));
    }

    /**
     * The limits from {@code micronaut.server.max-request-size} and {@code micronaut.server.max-request-buffer-size},
     * for runtimes to apply while a request body is read.
     *
     * @return The body size limits
     * @since 6.2.0
     */
    protected @NonNull BodySizeLimits bodySizeLimits() {
        return applicationContext.findBean(HttpServerConfiguration.class)
            .map(configuration -> new BodySizeLimits(configuration.getMaxRequestSize(), configuration.getMaxRequestBufferSize()))
            .orElse(BodySizeLimits.UNLIMITED);
    }

    /**
     * @return The application context for the function.
     */
    public ApplicationContext getApplicationContext() {
        return this.applicationContext;
    }

    /**
     * @return The message body handler registry.
     */
    public MessageBodyHandlerRegistry getMessageBodyHandlerRegistry() {
        return messageBodyHandlerRegistry;
    }

    /**
     * Handle the give native request and response.
     *
     * @param request  The request
     * @param response The response
     */
    public void service(REQ request, RES response) {
        ServletExchange<REQ, RES> exchange = createExchange(request, response);
        service(exchange);
    }

    /**
     * Handle the give native request and response and return the {@link ServletExchange} object.
     *
     * @param request  The request
     * @param response The response
     * @return The {@link ServletExchange} object
     */
    public ServletExchange<REQ, RES> exchange(REQ request, RES response) {
        ServletExchange<REQ, RES> servletExchange = createExchange(request, response);
        return exchange(servletExchange);
    }

    /**
     * Handle the give native request and response and return the {@link ServletExchange} object.
     *
     * @param exchange The exchange
     * @return The {@link ServletExchange} object
     */
    public ServletExchange<REQ, RES> exchange(ServletExchange<REQ, RES> exchange) {
        service(Objects.requireNonNull(exchange, "The exchange cannot be null"));
        return exchange;
    }

    @Override
    public boolean isRunning() {
        return getApplicationContext().isRunning();
    }

    private static void transfer(ExecutionResult executionResult, ServletExchange<?, ?> exchange, boolean async, Runnable onComplete) {
        ByteBodyHttpResponse<?> byteBodyResponse = executionResult.byteBodyHttpResponse;
        boolean debugEnabled = LOG.isDebugEnabled();
        if (debugEnabled) {
            if (byteBodyResponse == null) {
                LOG.debug("Request [{} - {}] completed commited manually", exchange.getRequest().getMethodName(), exchange.getRequest().getUri());
            } else {
                LOG.debug("Request [{} - {}] completed successfully", exchange.getRequest().getMethodName(), exchange.getRequest().getUri());
            }
        }
        if (byteBodyResponse == null) {
            onComplete.run();
            return;
        }

        traceHeaders(byteBodyResponse.getHeaders());

        ServletHttpResponse<?, ?> servletResponse = exchange.getResponse();
        if (byteBodyResponse.getHeaders() != exchange.getResponse().getHeaders()) {
            servletResponse.status(byteBodyResponse.code(), byteBodyResponse.reason());
            HttpHeaders sourceHeaders = byteBodyResponse.getHeaders();
            MutableHttpHeaders servletResponseHeaders = servletResponse.getHeaders();
            Set<String> sourceNames = new LinkedHashSet<>(sourceHeaders.names());
            for (String servletResponseHeader : List.copyOf(servletResponseHeaders.names())) {
                if (sourceNames.remove(servletResponseHeader)) {
                    List<String> all = sourceHeaders.getAll(servletResponseHeader);
                    boolean previouslyRemovedCalled = false;
                    for (String v : all) {
                        if (!previouslyRemovedCalled) {
                            // Some implementations don't like to remove some headers so we don't use remove method
                            servletResponseHeaders.set(servletResponseHeader, v);
                            previouslyRemovedCalled = true;
                        } else {
                            servletResponseHeaders.add(servletResponseHeader, v);
                        }
                    }
                } else {
                    if (debugEnabled) {
                        LOG.debug("Request [{} - {}] custom native response header '{}': '{}'",
                            exchange.getRequest().getMethodName(),
                            exchange.getRequest().getUri(),
                            servletResponseHeader,
                            servletResponseHeaders.get(servletResponseHeader));
                    }
                }
            }
            for (String k : sourceNames) {
                sourceHeaders.getAll(k).forEach(v -> servletResponseHeaders.add(k, v));
            }
        }
        if (byteBodyResponse.byteBody() instanceof AvailableByteBody available && available.length() == 0) {
            // special case, don't call getOutputStream. the controller may have written manually.
            onComplete.run();
        } else if (async && !(byteBodyResponse.byteBody() instanceof AvailableByteBody)) {
            // a body that is still being produced is written as it arrives, through a WriteListener; a body that
            // is already complete is written below on this thread instead, because the listener costs a dispatch
            // through the container on every response and buys nothing when there is nothing to wait for
            servletResponse.stream(byteBodyResponse.byteBody().move()).whenComplete((ignored, t) -> {
                if (t != null) {
                    if (t instanceof EOFException) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Error while writing response body", t);
                        }
                    } else {
                        if (LOG.isWarnEnabled()) {
                            LOG.warn("Error while writing response body", t);
                        }
                    }
                }
                onComplete.run();
            });
        } else {
            byteBodyResponse.byteBody().expectedLength()
                .ifPresent(l -> servletResponse.getHeaders().set(HttpHeaders.CONTENT_LENGTH, String.valueOf(l)));
            try (InputStream is = byteBodyResponse.byteBody().toInputStream()) {
                is.transferTo(servletResponse.getOutputStream());
            } catch (IOException e) {
                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, Optional.ofNullable(e.getMessage()).orElse(e.toString()));
            }
            onComplete.run();
        }
    }

    /**
     * @return The number of requests received and not yet terminated
     * @since 6.2.0
     */
    public long getActiveRequests() {
        return activeRequests.get();
    }

    /**
     * Waits for every active request to terminate. The caller is expected to have stopped the server accepting new
     * requests first; this only tracks what is already in flight.
     *
     * @return A stage that completes when no request is active
     * @since 6.2.0
     */
    public @NonNull CompletionStage<Void> awaitIdle() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!drained.compareAndSet(null, future)) {
            return drained.get();
        }
        if (activeRequests.get() == 0) {
            future.complete(null);
        }
        return future;
    }

    private void requestFinished() {
        if (activeRequests.decrementAndGet() == 0) {
            CompletableFuture<Void> future = drained.get();
            if (future != null) {
                future.complete(null);
            }
        }
    }

    /**
     * Handles a {@link ServletExchange}.
     *
     * @param exchange The exchange
     */
    public void service(ServletExchange<REQ, RES> exchange) {
        final long time = System.currentTimeMillis();
        // every exit path has to run this exactly once: it closes the request byte body, runs the disposal
        // resources that delete multipart temp files, and publishes the terminated event. The error paths used to
        // return without it, leaking a temp file per failed upload
        activeRequests.incrementAndGet();
        AtomicBoolean terminated = new AtomicBoolean();
        Runnable requestTerminated = () -> {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            requestFinished();
            applicationContext.publishEvent(new HttpRequestTerminatedEvent(exchange.getRequest()));
            exchange.close();
            if (LOG.isTraceEnabled()) {
                final HttpRequest<? super Object> r = exchange.getRequest();
                LOG.trace("Executed HTTP Request [{} {}] in: {}ms",
                    r.getMethod(),
                    r.getPath(),
                    (System.currentTimeMillis() - time)
                );
            }
        };

        final HttpRequest<Object> req = exchange.getRequest();
        applicationContext.publishEvent(new HttpRequestReceivedEvent(req));

        ServletWebSocketUpgrader upgrader = resolveWebSocketUpgrader(req);
        if (upgrader != null) {
            serviceWebSocketUpgrade(exchange, req, upgrader, requestTerminated);
            return;
        }

        ServletRequestLifecycle lc = new ServletRequestLifecycle(routeExecutor);

        if (exchange.getRequest().isAsyncSupported()) {
            serviceAsync(exchange, req, lc, requestTerminated);
        } else {
            serviceBlocking(exchange, req, lc, requestTerminated);
        }
    }

    /**
     * Handles a request on the container's asynchronous path, releasing the container thread while the route runs.
     *
     * @param exchange The exchange
     * @param req The request
     * @param lc The request lifecycle
     * @param requestTerminated Runs once the request is finished with, however it ends
     */
    private void serviceAsync(ServletExchange<REQ, RES> exchange,
                              HttpRequest<Object> req,
                              ServletRequestLifecycle lc,
                              Runnable requestTerminated) {
        exchange.getRequest().executeAsync(ctx -> PropagatedContext.getOrEmpty().plus(new ServerHttpRequestContext(req)).propagate(() -> {
            // completing the async context twice throws, so this has to run exactly once however the exchange ends
            AtomicBoolean finished = new AtomicBoolean();
            Runnable finish = () -> {
                if (finished.compareAndSet(false, true)) {
                    ctx.complete();
                    requestTerminated.run();
                }
            };
            lc.handleNormal(req)
                .flatMap(response -> process(response, req, exchange.getResponse()))
                .onComplete((bbhr, t) -> {
                    if (t == null) {
                        try {
                            transfer(bbhr, exchange, true, finish);
                        } catch (Exception transferFailure) {
                            // transfer throws on a write failure, before it can run the callback itself
                            handleFallback(exchange.getResponse(), transferFailure);
                            finish.run();
                        }
                    } else {
                        handleFallback(exchange.getResponse(), t);
                        finish.run();
                    }
                });
            return null;
        }));
    }

    /**
     * Whether this request should be switched over to the WebSocket protocol.
     *
     * @param request The request
     * @return The upgrader to use, or {@code null} when this is not a WebSocket upgrade or
     * the server has no WebSocket support on the classpath
     */
    private @Nullable ServletWebSocketUpgrader resolveWebSocketUpgrader(HttpRequest<?> request) {
        return ServletWebSocketUpgrader.isWebSocketUpgrade(request) ? webSocketUpgrader.get() : null;
    }

    /**
     * Handles a request on a container that does not support asynchronous processing, by
     * blocking the container thread until the response is ready.
     *
     * @param exchange          The exchange
     * @param req               The request
     * @param lc                The request lifecycle
     * @param requestTerminated Callback to run once the request is finished with
     */
    private void serviceBlocking(ServletExchange<REQ, RES> exchange,
                                 HttpRequest<Object> req,
                                 ServletRequestLifecycle lc,
                                 Runnable requestTerminated) {
        ExecutionResult executionResult;
        CompletableFuture<ExecutionResult> cfExecutionResult = PropagatedContext.getOrEmpty().plus(new ServerHttpRequestContext(req)).propagate(() -> lc.handleNormal(req)
            .flatMap(response -> process(response, req, exchange.getResponse())).toCompletableFuture());
        try {
            executionResult = cfExecutionResult.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            requestTerminated.run();
            return;
        } catch (Throwable ee) {
            handleFallback(exchange.getResponse(), Optional.ofNullable(ee.getCause()).orElse(ee));
            requestTerminated.run();
            return;
        }
        try {
            transfer(executionResult, exchange, false, requestTerminated);
        } finally {
            // transfer throws on a write failure, before it can run the callback itself
            requestTerminated.run();
        }
    }

    /**
     * Handles a WebSocket upgrade request.
     *
     * <p>The Micronaut filter chain, and therefore security, runs against the handshake
     * exactly as it does on the Netty server. The upgrade only proceeds when no filter
     * substituted a response; otherwise that response is written as ordinary HTTP.</p>
     *
     * <p>This runs synchronously on the thread the container is dispatching on and does
     * not enter asynchronous mode, because servlet containers require the protocol switch
     * to happen while the original request is still being dispatched.</p>
     *
     * @param exchange          The exchange
     * @param req               The request
     * @param upgrader          The upgrader
     * @param requestTerminated Callback to run once the HTTP request is finished with
     */
    private void serviceWebSocketUpgrade(ServletExchange<REQ, RES> exchange,
                                         HttpRequest<Object> req,
                                         ServletWebSocketUpgrader upgrader,
                                         Runnable requestTerminated) {
        WebSocketUpgradeRequestLifecycle lc = new WebSocketUpgradeRequestLifecycle(routeExecutor, router.get());
        HttpResponse<?> filteredResponse;
        try {
            filteredResponse = PropagatedContext.getOrEmpty()
                .plus(new ServerHttpRequestContext(req))
                .propagate(() -> lc.handle(req).toCompletableFuture())
                .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.getResponse().status(HttpStatus.SERVICE_UNAVAILABLE);
            requestTerminated.run();
            return;
        } catch (Throwable e) {
            handleFallback(exchange.getResponse(), Optional.ofNullable(e.getCause()).orElse(e));
            requestTerminated.run();
            return;
        }

        UriRouteMatch<Object, Object> routeMatch = lc.getRouteMatch();
        if (lc.shouldProceedNormally() && routeMatch != null) {
            switchProtocols(exchange, req, upgrader, routeMatch, filteredResponse, requestTerminated);
            return;
        }

        ExecutionResult executionResult;
        try {
            executionResult = process(filteredResponse, req, exchange.getResponse()).toCompletableFuture().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.getResponse().status(HttpStatus.SERVICE_UNAVAILABLE);
            requestTerminated.run();
            return;
        } catch (Throwable e) {
            handleFallback(exchange.getResponse(), Optional.ofNullable(e.getCause()).orElse(e));
            requestTerminated.run();
            return;
        }
        transfer(executionResult, exchange, false, requestTerminated);
    }

    /**
     * Hands the connection to the WebSocket implementation, reporting a runtime that cannot
     * upgrade as {@code 501 Not Implemented} rather than a generic failure.
     *
     * @param exchange          The exchange
     * @param req               The request
     * @param upgrader          The upgrader
     * @param routeMatch        The matched WebSocket route
     * @param handshakeResponse The response the filter chain produced
     * @param requestTerminated Callback to run once the HTTP request is finished with
     */
    private void switchProtocols(ServletExchange<REQ, RES> exchange,
                                 HttpRequest<Object> req,
                                 ServletWebSocketUpgrader upgrader,
                                 UriRouteMatch<Object, Object> routeMatch,
                                 HttpResponse<?> handshakeResponse,
                                 Runnable requestTerminated) {
        try {
            upgrader.upgrade(exchange, req, routeMatch, handshakeResponse);
        } catch (HttpStatusException e) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Cannot upgrade request [{} - {}] to WebSocket: {}", req.getMethodName(), req.getUri(), e.getMessage());
            }
            exchange.getResponse().status(e.getStatus(), e.getMessage());
            requestTerminated.run();
            return;
        } catch (Throwable e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Error upgrading request [{} - {}] to WebSocket: {}", req.getMethodName(), req.getUri(), e.getMessage(), e);
            }
            handleFallback(exchange.getResponse(), e);
            requestTerminated.run();
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Request [{} - {}] upgraded to WebSocket", req.getMethodName(), req.getUri());
        }
        // The connection now belongs to the WebSocket implementation, so the exchange is
        // deliberately not closed here: closing it would touch container streams that the
        // protocol switch has already taken over.
        applicationContext.publishEvent(new HttpRequestTerminatedEvent(req));
    }

    private ExecutionFlow<ExecutionResult> process(HttpResponse<?> response,
                                                   HttpRequest<Object> req,
                                                   ServletHttpResponse<?, ?> shr) {
        if (shr.isCommitted()) {
            return ExecutionFlow.just(new ExecutionResult(null));
        }
        return new ServletResponseLifecycle(req).encodeHttpResponseSafe(req, response).map(ExecutionResult::new);
    }

    private static void handleFallback(ServletHttpResponse<?, ?> shr, Throwable t) {
        if (LOG.isWarnEnabled()) {
            LOG.warn("Failed to encode error response", t);
        }
        shr.status(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private Optional<FileCustomizableResponseType> matchFile(String path) {
        Optional<URL> optionalUrl = staticResourceResolver.resolve(path);

        if (optionalUrl.isPresent()) {
            try {
                URL url = optionalUrl.get();
                if (url.getProtocol().equals("file")) {
                    File file = Paths.get(url.toURI()).toFile();
                    if (file.exists() && !file.isDirectory() && file.canRead()) {
                        return Optional.of(new SystemFile(file));
                    }
                }

                return Optional.of(new StreamedFile(url));
            } catch (URISyntaxException e) {
                //no-op
            }
        }

        return Optional.empty();
    }

    private static void traceHeaders(HttpHeaders httpHeaders) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("-----");
            httpHeaders.forEach((name, values) -> LOG.trace("{} : {}", name, values));
            LOG.trace("-----");
        }
    }

    @Override
    public void close() {
        if (applicationContext.isRunning()) {
            applicationContext.close();
        }
    }

    @NonNull
    @Override
    public ServletHttpHandler<REQ, RES> start() {
        if (!applicationContext.isRunning()) {
            applicationContext.start();
        }
        return this;
    }

    @NonNull
    @Override
    public ServletHttpHandler<REQ, RES> stop() {
        close();
        return this;
    }

    /**
     * Creates the {@link ServletExchange} object.
     *
     * @param request  The request
     * @param response The response
     * @return The exchange object
     */
    protected abstract ServletExchange<REQ, RES> createExchange(REQ request, RES response);

    private final class ServletRequestLifecycle extends RequestLifecycle {
        ServletRequestLifecycle(RouteExecutor routeExecutor) {
            super(routeExecutor);
        }

        ExecutionFlow<HttpResponse<?>> handleNormal(HttpRequest<?> request) {
            return normalFlow(request);
        }

        @Override
        protected @Nullable FileCustomizableResponseType findFile(HttpRequest<?> request) {
            return matchFile(request.getPath()).orElse(null);
        }
    }

    private final class ServletResponseLifecycle extends ResponseLifecycle {
        private static final ByteBodyFactory BBF = ByteBodyFactory.createDefault(ByteArrayBufferFactory.INSTANCE);
        /**
         * Whether the Netty HTTP types are on the classpath; {@link NettyStreamedResponses} must not be loaded
         * otherwise.
         */
        private static final boolean NETTY_PRESENT = ClassUtils.isPresent(
            "io.micronaut.http.netty.NettyHttpResponseBuilder", ServletHttpHandler.class.getClassLoader()
        );

        private final HttpRequest<?> request;

        ServletResponseLifecycle(HttpRequest<?> request) {
            super(routeExecutor, messageBodyHandlerRegistry, conversionService, BBF);
            this.request = request;
        }

        @Override
        protected @NonNull Executor ioExecutor() {
            return ioExecutor.get();
        }

        /**
         * A response with no object body may still carry bytes: the Netty HTTP client's {@code ProxyHttpClient}
         * returns the upstream response as a content stream that only the Netty types expose. The Netty server
         * unwraps it in its response lifecycle, and so must this one, or a proxying filter answers with an empty
         * body (micronaut-core#9725).
         */
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected ExecutionFlow<? extends ByteBodyHttpResponse<?>> encodeNoBody(HttpResponse<?> response) {
            if (NETTY_PRESENT) {
                Publisher<ReadBuffer> content = NettyStreamedResponses.streamedContent(response, BBF.readBufferFactory());
                if (content != null) {
                    return LazySendingSubscriber.create(content)
                        .map(buffers -> (ByteBodyHttpResponse<?>) ByteBodyHttpResponseWrapper.wrap(
                            response,
                            BBF.adapt(buffers, response.getHeaders().contentLength())
                        ))
                        .onErrorResume(e -> (ExecutionFlow) handleStreamingError(request, e));
                }
            }
            return super.encodeNoBody(response);
        }
    }

    private record ExecutionResult(@Nullable ByteBodyHttpResponse<?> byteBodyHttpResponse) {
    }
}
