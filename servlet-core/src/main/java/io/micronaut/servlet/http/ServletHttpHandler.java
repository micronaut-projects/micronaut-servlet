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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.io.Writable;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.body.DynamicMessageBodyWriter;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.context.ServerHttpRequestContext;
import io.micronaut.http.context.event.HttpRequestReceivedEvent;
import io.micronaut.http.context.event.HttpRequestTerminatedEvent;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.RequestLifecycle;
import io.micronaut.http.server.RouteExecutor;
import io.micronaut.http.server.types.files.FileCustomizableResponseType;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.http.server.types.files.SystemFile;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.resource.StaticResourceResolver;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.EOFException;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
    private final Map<Class<?>, ServletResponseEncoder<?>> responseEncoders;
    private final StaticResourceResolver staticResourceResolver;

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
        //noinspection unchecked
        this.responseEncoders = applicationContext.streamOfType(ServletResponseEncoder.class)
            .collect(Collectors.toMap(
                ServletResponseEncoder::getResponseType,
                (o) -> o
            ));
        this.staticResourceResolver = applicationContext.getBean(StaticResourceResolver.class);
        this.routeExecutor = applicationContext.getBean(RouteExecutor.class);
        this.conversionService = conversionService;

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

    /**
     * Handles a {@link DefaultServletExchange}.
     *
     * @param exchange The exchange
     */
    public void service(ServletExchange<REQ, RES> exchange) {
        final long time = System.currentTimeMillis();
        Consumer<HttpResponse<?>> requestTerminated = ignore -> {
            applicationContext.publishEvent(new HttpRequestTerminatedEvent(exchange.getRequest()));
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
            exchange.getRequest().executeAsync(asyncExecution -> {
                try (PropagatedContext.Scope ignore = PropagatedContext.getOrEmpty().plus(new ServerHttpRequestContext(req)).propagate()) {
                    lc.handleNormal(req).onComplete((response, throwable) -> onComplete(
                            exchange,
                            req,
                            response == null ? null : response.toMutableResponse(),
                            throwable,
                            httpResponse -> {
                                asyncExecution.complete();
                                requestTerminated.accept(httpResponse);
                            }
                    ));
                }
            });
        } else {
            try (PropagatedContext.Scope ignore = PropagatedContext.getOrEmpty().plus(new ServerHttpRequestContext(req)).propagate()) {
                CompletableFuture<?> termination = new CompletableFuture<>();
                lc.handleNormal(req)
                    .onComplete((response, throwable) -> {
                        try {
                            onComplete(
                                    exchange,
                                    req,
                                    response == null ? null : response.toMutableResponse(),
                                    throwable,
                                    requestTerminated
                            );
                        } finally {
                            termination.complete(null);
                        }
                    });
                termination.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new AssertionError("we only call complete, shouldn't happen", e);
            }
        }
    }

    private void onComplete(ServletExchange<REQ, RES> exchange,
                            HttpRequest<Object> req,
                            MutableHttpResponse<?> response,
                            Throwable throwable,
                            Consumer<HttpResponse<?>> responsePublisherCallback) {
        if (throwable != null) {
            response = routeExecutor.createDefaultErrorResponse(req, throwable);
        }
        if (response != null) {
            String methodName = req.getMethodName();
            URI uri = req.getUri();
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Request [{} - {}] completed successfully", methodName, uri);
                }
                encodeResponse(exchange, req, response, responsePublisherCallback);
            } catch (Throwable e) {
                if (e instanceof HttpStatusException statusException) {
                    response = HttpResponse.status(statusException.getStatus()).body(statusException.getBody().orElse(null));
                } else {
                    response = routeExecutor.createDefaultErrorResponse(req, e);
                }
                try {
                    encodeResponse(exchange, req, response, responsePublisherCallback);
                } catch (Throwable e2) {
                    LOG.error("Request [{} - {}] completed with error: {}", methodName, uri, e2.getMessage(), e2);
                    responsePublisherCallback.accept(null);
                    return;
                }
            }
            if (throwable != null) {
                LOG.error("Request [{} - {}] completed with error: {}", methodName, uri, throwable.getMessage(), throwable);
            } else {
                LOG.debug("Request [{} - {}] completed successfully", methodName, uri);
            }
        } else {
            responsePublisherCallback.accept(null);
        }
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

    private void traceHeaders(HttpHeaders httpHeaders) {
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

    private void encodeResponse(ServletExchange<REQ, RES> exchange,
                                HttpRequest<?> request,
                                MutableHttpResponse<?> response,
                                Consumer<HttpResponse<?>> responsePublisherCallback) {
        try {
            Object body = response.getBody().orElse(null);

            if (LOG.isDebugEnabled()) {
                LOG.debug("Sending response {}", response.status());
                traceHeaders(response.getHeaders());
            }

            @SuppressWarnings("rawtypes")
            Optional<RouteInfo> routeInfoAttribute = response.getAttribute(HttpAttributes.ROUTE_INFO, RouteInfo.class);
            AnnotationMetadata routeAnnotationMetadata = routeInfoAttribute
                .map(AnnotationMetadataProvider::getAnnotationMetadata)
                .orElse(AnnotationMetadata.EMPTY_METADATA);
            @SuppressWarnings("unchecked")
            Argument<Object> bodyArgument = routeInfoAttribute.map(RouteInfo::getResponseBodyType).orElse(null);
            boolean isVoid = routeInfoAttribute.map(RouteInfo::isVoid).orElse(false);
            ServletHttpResponse<RES, ?> servletResponse = exchange.getResponse();
            servletResponse.status(response.status(), response.reason());

            if (body != null && !isVoid) {
                Class<?> bodyType = body.getClass();
                if (bodyArgument == null || !bodyArgument.isInstance(body) || bodyArgument.getType().equals(Object.class)) {
                    bodyArgument = (Argument<Object>) Argument.of(bodyType);
                }
                ServletResponseEncoder<Object> responseEncoder = (ServletResponseEncoder<Object>) responseEncoders.get(bodyType);
                boolean asyncSupported = exchange.getRequest().isAsyncSupported();
                if (responseEncoder != null) {
                    if (asyncSupported) {
                        Flux.from(responseEncoder.encode(exchange, routeAnnotationMetadata, body))
                            .subscribe(responsePublisherCallback);
                    } else {
                        // NOTE[moss]: blockLast() here *was* subscribe(), but that returns immediately, which was
                        // sometimes allowing the main response publisher to complete before this responseEncoder
                        // could fill out the response! Blocking here will ensure that the response is filled out
                        // before the main response publisher completes. This will be improved later to avoid the block.
                        Flux.from(responseEncoder.encode(exchange, routeAnnotationMetadata, body)).blockLast();
                    }
                    return;
                }

                MediaType mediaType = response.getContentType().orElse(null);
                if (mediaType == null) {
                    mediaType = routeInfoAttribute
                        .map(routeInfo -> {
                            final Produces ann = bodyType.getAnnotation(Produces.class);
                            if (ann != null) {
                                final String[] v = ann.value();
                                if (ArrayUtils.isNotEmpty(v)) {
                                    return new MediaType(v[0]);
                                }
                            }
                            return routeExecutor.resolveDefaultResponseContentType(request, routeInfo);
                        })
                        // RouteExecutor will pick json by default, so we do too
                        .orElse(MediaType.APPLICATION_JSON_TYPE);
                    response.contentType(mediaType);
                }

                MessageBodyWriter<Object> messageBodyWriter = null;
                if (!(body instanceof HttpStatus)) {
                    messageBodyWriter = routeInfoAttribute.map(RouteInfo::getMessageBodyWriter).orElse(null);
                    if (messageBodyWriter == null) {
                        MediaType finalMediaType = mediaType;
                        Argument<Object> finalBodyArgument = bodyArgument;
                        Optional<MessageBodyWriter<Object>> writer = messageBodyHandlerRegistry.findWriter(bodyArgument, List.of(mediaType));
                        if (writer.isEmpty() && mediaType.equals(MediaType.TEXT_PLAIN_TYPE) && ClassUtils.isJavaBasicType(body.getClass())) {
                            // TODO: remove after Core 4.6
                            writer = (Optional) messageBodyHandlerRegistry.findWriter(Argument.STRING, List.of(MediaType.TEXT_PLAIN_TYPE));
                        }
                        messageBodyWriter = writer
                            .orElseThrow(() -> new CodecException("Cannot encode value of argument [" + finalBodyArgument + "]. No possible encoders found for media type: " + finalMediaType));
                    }
                }

                setHeadersFromMetadata(servletResponse, routeAnnotationMetadata, body);
                if (Publishers.isConvertibleToPublisher(body)) {
                    boolean isSingle = Publishers.isSingle(body.getClass());
                    Publisher<?> publisher = Publishers.convertPublisher(conversionService, body, Publisher.class);
                    if (isSingle) {
                        if (asyncSupported) {
                            Flux<Object> flux = Flux.from(publisher);
                            flux.next().switchIfEmpty(Mono.just(response)).subscribe(bodyValue -> {
                                MutableHttpResponse<?> nextResponse;
                                if (bodyValue instanceof MutableHttpResponse) {
                                    nextResponse = ((MutableHttpResponse<?>) bodyValue);
                                    if (response == nextResponse) {
                                        nextResponse.body(null);
                                    }
                                } else {
                                    nextResponse = response.body(bodyValue);
                                }
                                // Call encoding again, the body might need to be encoded
                                encodeResponse(exchange, request, nextResponse, responsePublisherCallback);
                            });
                            return;
                        } else {
                            // fallback to blocking
                            body = Mono.from(publisher).block();
                            response.body(body);
                        }
                    } else {
                        // stream case
                        if (asyncSupported) {
                            Mono.from(servletResponse.stream(publisher)).subscribe(responsePublisherCallback, throwable -> {
                                responsePublisherCallback.accept(null);
                            });
                            return;
                        } else {
                            // fallback to blocking

                            // LazyOutputStream must not be initialized before publisher exceptions
                            // are checked
                            try (OutputStream outputStream = new LazyOutputStream(servletResponse)) {
                                boolean json = mediaType.equals(MediaType.APPLICATION_JSON_TYPE);
                                boolean first = true;
                                for (Object o : Flux.from(publisher).toIterable()) {
                                    if (json) {
                                        if (!first) {
                                            outputStream.write(',');
                                        } else {
                                            outputStream.write('[');
                                        }
                                    }
                                    first = false;

                                    messageBodyWriter.writeTo(
                                        bodyArgument,
                                        mediaType,
                                        o,
                                        response.getHeaders(),
                                        new UncloseableOutputStream(outputStream)
                                    );
                                }
                                if (json) {
                                    if (first) {
                                        outputStream.write('[');
                                    }
                                    outputStream.write(']');
                                }
                            } catch (IOException e) {
                                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                            }
                            responsePublisherCallback.accept(response);
                            return;
                        }
                    }
                }
                if (body instanceof HttpStatus httpStatus) {
                    servletResponse.status(httpStatus);
                } else {
                    try (OutputStream outputStream = servletResponse.getOutputStream()) {
                        if (body instanceof Writable w) {
                            w.writeTo(outputStream);
                        } else {
                            messageBodyWriter.writeTo(
                                bodyArgument,
                                mediaType,
                                body,
                                response.getHeaders(),
                                outputStream
                            );
                        }
                    } catch (IOException e) {
                        throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                    }
                }
            }
            responsePublisherCallback.accept(response);
        } catch (CodecException e) {
            if (!(e.getCause() instanceof EOFException)) {
                throw e;
            }
        }
    }

    private void setHeadersFromMetadata(MutableHttpResponse<?> res, AnnotationMetadata annotationMetadata, Object result) {
        if (res.getContentType().isEmpty()) {
            final String contentType = annotationMetadata.stringValue(Produces.class)
                .orElse(getDefaultMediaType(result));
            if (contentType != null) {
                res.contentType(contentType);
            }
        }

        final List<AnnotationValue<Header>> headers = annotationMetadata.getAnnotationValuesByType(Header.class);
        for (AnnotationValue<Header> header : headers) {
            final String value = header.stringValue().orElse(null);
            final String name = header.stringValue("name").orElse(null);
            if (name != null && value != null) {
                res.header(name, value);
            }
        }
    }

    private String getDefaultMediaType(Object result) {
        if (result != null) {
            return MediaType.APPLICATION_JSON;
        }
        return null;
    }

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
