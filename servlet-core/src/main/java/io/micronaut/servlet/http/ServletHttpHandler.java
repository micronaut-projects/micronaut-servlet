/*
 * Copyright 2017-2020 original authors
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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.util.SupplierUtil;
import io.micronaut.http.ByteBodyHttpResponse;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.body.AvailableByteBody;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.context.ServerHttpRequestContext;
import io.micronaut.http.context.event.HttpRequestReceivedEvent;
import io.micronaut.http.context.event.HttpRequestTerminatedEvent;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.RequestLifecycle;
import io.micronaut.http.server.ResponseLifecycle;
import io.micronaut.http.server.RouteExecutor;
import io.micronaut.http.server.types.files.FileCustomizableResponseType;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.http.server.types.files.SystemFile;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.web.router.resource.StaticResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
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
    private final MediaTypeCodecRegistry mediaTypeCodecRegistry;
    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;
    private final StaticResourceResolver staticResourceResolver;
    private final Supplier<Executor> ioExecutor;

    /**
     * Default constructor.
     *
     * @param applicationContext The application context
     * @param conversionService  The conversion service
     */
    protected ServletHttpHandler(ApplicationContext applicationContext, ConversionService conversionService) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "The application context cannot be null");
        this.mediaTypeCodecRegistry = applicationContext.getBean(MediaTypeCodecRegistry.class);
        this.messageBodyHandlerRegistry = applicationContext.getBean(MessageBodyHandlerRegistry.class);
        this.staticResourceResolver = applicationContext.getBean(StaticResourceResolver.class);
        this.routeExecutor = applicationContext.getBean(RouteExecutor.class);
        this.conversionService = conversionService;
        this.ioExecutor = SupplierUtil.memoized(() -> applicationContext.getBean(Executor.class, Qualifiers.byName(TaskExecutors.BLOCKING)));

        // hack for bug fixed in Micronaut 1.3.3
        applicationContext.getEnvironment()
            .addConverter(HttpRequest.class, HttpRequest.class, httpRequest -> httpRequest);
    }

    /**
     * Default constructor.
     *
     * @param applicationContext The application context
     * @deprecated Use {@link #ServletHttpHandler(ApplicationContext, ConversionService)}
     */
    @Deprecated
    public ServletHttpHandler(ApplicationContext applicationContext) {
        this(applicationContext, ConversionService.SHARED);
    }

    /**
     * @return The application context for the function.
     */
    public ApplicationContext getApplicationContext() {
        return this.applicationContext;
    }

    /**
     * @return The media type codec registry.
     */
    public MediaTypeCodecRegistry getMediaTypeCodecRegistry() {
        return mediaTypeCodecRegistry;
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

    private static void transfer(ByteBodyHttpResponse<?> byteBodyResponse, ServletExchange<?, ?> exchange, boolean async, Runnable onComplete) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Request [{} - {}] completed successfully", exchange.getRequest().getMethodName(), exchange.getRequest().getUri());
        }
        traceHeaders(byteBodyResponse.getHeaders());

        ServletHttpResponse<?, ?> servletResponse = exchange.getResponse();
        if (byteBodyResponse.getHeaders() != exchange.getResponse().getHeaders()) {
            servletResponse.status(byteBodyResponse.code(), byteBodyResponse.reason());
            HttpHeaders sourceHeaders = byteBodyResponse.getHeaders();
            MutableHttpHeaders targetHeaders = servletResponse.getHeaders();
            Set<String> sourceNames = new LinkedHashSet<>(sourceHeaders.names());
            for (String k : targetHeaders.names()) {
                if (sourceNames.remove(k)) {
                    List<String> all = sourceHeaders.getAll(k);
                    targetHeaders.remove(k);
                    all.forEach(v -> targetHeaders.add(k, v));
                } else {
                    targetHeaders.remove(k);
                }
            }
            for (String k : sourceNames) {
                sourceHeaders.getAll(k).forEach(v -> targetHeaders.add(k, v));
            }
        }
        if (byteBodyResponse.byteBody() instanceof AvailableByteBody available && available.length() == 0) {
            // special case, don't call getOutputStream. the controller may have written manually.
            onComplete.run();
        } else if (async) {
            servletResponse.stream(byteBodyResponse.byteBody().move()).whenComplete((ignored, t) -> {
                if (t != null) {
                    if (LOG.isWarnEnabled()) {
                        LOG.warn("Error while writing response body", t);
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
                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            }
            onComplete.run();
        }
    }

    /**
     * Handles a {@link DefaultServletExchange}.
     *
     * @param exchange The exchange
     */
    public void service(ServletExchange<REQ, RES> exchange) {
        final long time = System.currentTimeMillis();
        Runnable requestTerminated = () -> {
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

        ServletRequestLifecycle lc = new ServletRequestLifecycle(routeExecutor);

        if (exchange.getRequest().isAsyncSupported()) {
            exchange.getRequest().executeAsync(ctx -> {
                try (PropagatedContext.Scope ignore = PropagatedContext.getOrEmpty().plus(new ServerHttpRequestContext(req)).propagate()) {
                    lc.handleNormal(req)
                        .flatMap(response -> new ServletResponseLifecycle().encodeHttpResponseSafe(req, response))
                        .onComplete((bbhr, t) -> {
                            if (t == null) {
                                transfer(bbhr, exchange, true, () -> {
                                    ctx.complete();
                                    requestTerminated.run();
                                });
                            } else {
                                handleFallback(exchange.getResponse(), t);
                                ctx.complete();
                            }
                        });
                }
            });
        } else {
            ByteBodyHttpResponse<?> bbhr;
            try (PropagatedContext.Scope ignore = PropagatedContext.getOrEmpty().plus(new ServerHttpRequestContext(req)).propagate()) {
                bbhr = lc.handleNormal(req)
                    .flatMap(response -> new ServletResponseLifecycle().encodeHttpResponseSafe(req, response)).toCompletableFuture().get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException ee) {
                handleFallback(exchange.getResponse(), ee.getCause());
                return;
            }
            transfer(bbhr, exchange, false, requestTerminated);
        }
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
     * Creates the {@link DefaultServletExchange} object.
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
        protected FileCustomizableResponseType findFile(HttpRequest<?> request) {
            return matchFile(request.getPath()).orElse(null);
        }
    }

    private final class ServletResponseLifecycle extends ResponseLifecycle {
        private static final ByteBodyFactory BBF = ByteBodyFactory.createDefault(ByteArrayBufferFactory.INSTANCE);

        public ServletResponseLifecycle() {
            super(routeExecutor, messageBodyHandlerRegistry, conversionService, BBF);
        }

        @Override
        protected @NonNull Executor ioExecutor() {
            return ioExecutor.get();
        }
    }

    private static final class LazyOutputStream extends OutputStream {
        private ServletHttpResponse<?, ?> response;
        private OutputStream stream;

        public LazyOutputStream(ServletHttpResponse<?, ?> response) {
            this.response = response;
        }

        private OutputStream stream() throws IOException {
            if (stream == null) {
                stream = response.getOutputStream();
                response = null;
            }
            return stream;
        }

        @Override
        public void write(int b) throws IOException {
            stream().write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            stream().write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }

    private static final class UncloseableOutputStream extends FilterOutputStream {
        public UncloseableOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            // do nothing
        }
    }
}
