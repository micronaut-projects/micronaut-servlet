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
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.io.Writable;
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
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.context.event.HttpRequestReceivedEvent;
import io.micronaut.http.context.event.HttpRequestTerminatedEvent;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.RouteExecutor;
import io.micronaut.http.server.binding.RequestArgumentSatisfier;
import io.micronaut.http.server.types.files.FileCustomizableResponseType;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.http.server.types.files.SystemFile;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.resource.StaticResourceResolver;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * An HTTP handler that can deal with Serverless requests.
 *
 * @param <Req> The request object
 * @param <Res> The response object
 * @author graemerocher
 * @since 1.2.0
 */
public abstract class ServletHttpHandler<Req, Res> implements AutoCloseable,
    LifeCycle<ServletHttpHandler<Req, Res>>, RouteExecutor.StaticResourceResponseFinder {
    /**
     * Logger to be used by subclasses for logging.
     */
    protected static final Logger LOG = LoggerFactory.getLogger(ServletHttpHandler.class);

    protected final ApplicationContext applicationContext;
    private final RequestArgumentSatisfier requestArgumentSatisfier;
    private final RouteExecutor routeExecutor;
    private final MediaTypeCodecRegistry mediaTypeCodecRegistry;
    private final Map<Class<?>, ServletResponseEncoder<?>> responseEncoders;
    private final StaticResourceResolver staticResourceResolver;

    /**
     * Default constructor.
     *
     * @param applicationContext The application context
     */
    public ServletHttpHandler(ApplicationContext applicationContext) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "The application context cannot be null");
        this.requestArgumentSatisfier = applicationContext.getBean(RequestArgumentSatisfier.class);
        this.mediaTypeCodecRegistry = applicationContext.getBean(MediaTypeCodecRegistry.class);
        //noinspection unchecked
        this.responseEncoders = applicationContext.streamOfType(ServletResponseEncoder.class)
            .collect(Collectors.toMap(
                ServletResponseEncoder::getResponseType,
                (o) -> o
            ));
        this.staticResourceResolver = applicationContext.getBean(StaticResourceResolver.class);
        this.routeExecutor = applicationContext.getBean(RouteExecutor.class);

        // hack for bug fixed in Micronaut 1.3.3
        applicationContext.getEnvironment()
            .addConverter(HttpRequest.class, HttpRequest.class, httpRequest -> httpRequest);
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
    public void service(Req request, Res response) {
        ServletExchange<Req, Res> exchange = createExchange(request, response);
        service(exchange);
    }

    /**
     * Handle the give native request and response and return the {@link ServletExchange} object.
     *
     * @param request  The request
     * @param response The response
     * @return The {@link ServletExchange} object
     */
    public ServletExchange<Req, Res> exchange(Req request, Res response) {
        ServletExchange<Req, Res> servletExchange = createExchange(request, response);
        return exchange(servletExchange);
    }

    /**
     * Handle the give native request and response and return the {@link ServletExchange} object.
     *
     * @param exchange The exchange
     * @return The {@link ServletExchange} object
     */
    public ServletExchange<Req, Res> exchange(ServletExchange<Req, Res> exchange) {
        service(Objects.requireNonNull(exchange, "The exchange cannot be null"));
        return exchange;
    }

    @Override
    public boolean isRunning() {
        return getApplicationContext().isRunning();
    }

    @Override
    public FileCustomizableResponseType find(HttpRequest<?> httpRequest) {
        return matchFile(httpRequest.getPath()).orElse(null);
    }

    /**
     * Handles a {@link DefaultServletExchange}.
     *
     * @param exchange The exchange
     */
    public void service(ServletExchange<Req, Res> exchange) {
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

        RouteExecutor.RequestBodyReader requestBodyReader = (routeMatch, httpRequest) -> {
            RouteMatch<?> route = requestArgumentSatisfier.fulfillArgumentRequirements(routeMatch, httpRequest, false);
            return ExecutionFlow.just(route);
        };

        if (exchange.getRequest().isAsyncSupported()) {
            exchange.getRequest().executeAsync(asyncExecution -> {
                routeExecutor.executeRoute(requestBodyReader, req, true, this)
                    .onComplete((response, throwable) -> onComplete(exchange, req, response, throwable, httpResponse -> {
                        asyncExecution.complete();
                        requestTerminated.accept(httpResponse);
                    }));
            });
        } else {
            routeExecutor.executeRoute(requestBodyReader, req, true, this)
                .onComplete((response, throwable) -> onComplete(exchange, req, response, throwable, requestTerminated));
        }
    }

    private void onComplete(ServletExchange<Req, Res> exchange,
                            HttpRequest<Object> req,
                            MutableHttpResponse<?> response,
                            Throwable throwable,
                            Consumer<HttpResponse<?>> responsePublisherCallback) {
        if (throwable != null) {
            response = routeExecutor.createDefaultErrorResponse(req, throwable);
        }
        if (response != null) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Request [{} - {}] completed successfully", req.getMethodName(), req.getUri());
                }
                encodeResponse(exchange, req, response, responsePublisherCallback);
            } catch (Throwable e) {
                response = routeExecutor.createDefaultErrorResponse(req, e);
                try {
                    encodeResponse(exchange, req, response, responsePublisherCallback);
                } catch (Throwable e2) {
                    LOG.error("Request [{} - {}] completed with error: {}", req.getMethodName(), req.getUri(), e2.getMessage(), e2);
                    responsePublisherCallback.accept(null);
                    return;
                }
            }
            if (throwable != null) {
                LOG.error("Request [{} - {}] completed with error: {}", req.getMethodName(), req.getUri(), throwable.getMessage(), throwable);
            } else {
                LOG.debug("Request [{} - {}] completed successfully", req.getMethodName(), req.getUri());
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
    public ServletHttpHandler<Req, Res> start() {
        if (!applicationContext.isRunning()) {
            applicationContext.start();
        }
        return this;
    }

    @NonNull
    @Override
    public ServletHttpHandler<Req, Res> stop() {
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
    protected abstract ServletExchange<Req, Res> createExchange(Req request, Res response);

    private void encodeResponse(ServletExchange<Req, Res> exchange,
                                HttpRequest<?> request,
                                MutableHttpResponse<?> response,
                                Consumer<HttpResponse<?>> responsePublisherCallback) {
        final Object body = response.getBody().orElse(null);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Sending response {}", response.status());
            traceHeaders(response.getHeaders());
        }

        AnnotationMetadata routeAnnotationMetadata = response.getAttribute(HttpAttributes.ROUTE_INFO, RouteInfo.class)
            .map(AnnotationMetadataProvider::getAnnotationMetadata)
            .orElse(AnnotationMetadata.EMPTY_METADATA);
        if (body != null) {
            Class<?> bodyType = body.getClass();
            ServletResponseEncoder<Object> responseEncoder = (ServletResponseEncoder<Object>) responseEncoders.get(bodyType);
            if (responseEncoder != null) {
                if (exchange.getRequest().isAsyncSupported()) {
                    Flux.from(responseEncoder.encode(exchange, routeAnnotationMetadata, body))
                        .subscribe(responsePublisherCallback);
                    return;
                } else {
                    // NOTE[moss]: blockLast() here *was* subscribe(), but that returns immediately, which was
                    // sometimes allowing the main response publisher to complete before this responseEncoder
                    // could fill out the response! Blocking here will ensure that the response is filled out
                    // before the main response publisher completes. This will be improved later to avoid the block.
                    Flux.from(responseEncoder.encode(exchange, routeAnnotationMetadata, body)).blockLast();
                    // Continue blocking execution
                }
            }

            MediaType mediaType = response.getContentType().orElse(null);
            if (mediaType == null) {
                mediaType = response.getAttribute(HttpAttributes.ROUTE_INFO, RouteInfo.class)
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

            ServletHttpResponse<Res, ?> servletResponse = exchange.getResponse();

            setHeadersFromMetadata(servletResponse, routeAnnotationMetadata, body);

            if (Publishers.isConvertibleToPublisher(body)) {
                // This must be a streaming case, the route executor should eliminate single result publishers
                Publisher<?> publisher = Publishers.convertPublisher(body, Publisher.class);
                if (exchange.getRequest().isAsyncSupported()) {
                    Mono.from(servletResponse.stream(publisher)).subscribe(responsePublisherCallback);
                    return;
                } else {
                    // fallback to blocking
                    servletResponse.body(Flux.from(publisher).collectList().block());
                }
            }
            if (body instanceof HttpStatus) {
                servletResponse.status((HttpStatus) body);
            } else if (body instanceof CharSequence) {
                if (response.getContentType().isEmpty()) {
                    response.contentType(MediaType.APPLICATION_JSON);
                }
                try (BufferedWriter writer = servletResponse.getWriter()) {
                    writer.write(body.toString());
                    writer.flush();
                } catch (IOException e) {
                    throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                }
            } else if (body instanceof byte[] byteArray) {
                try (OutputStream outputStream = servletResponse.getOutputStream()) {
                    outputStream.write(byteArray);
                    outputStream.flush();
                } catch (IOException e) {
                    throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                }
            } else if (body instanceof Writable writable) {
                try (OutputStream outputStream = servletResponse.getOutputStream()) {
                    writable.writeTo(outputStream, response.getCharacterEncoding());
                    outputStream.flush();
                } catch (IOException e) {
                    throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                }
            } else {
                final MediaTypeCodec codec = mediaTypeCodecRegistry.findCodec(mediaType, bodyType).orElse(null);
                if (codec != null) {
                    try (OutputStream outputStream = servletResponse.getOutputStream()) {
                        codec.encode(body, outputStream);
                        outputStream.flush();
                    } catch (Throwable e) {
                        e.printStackTrace();
                        throw new CodecException("Failed to encode object [" + body + "] to content type [" + mediaType + "]: " + e.getMessage(), e);
                    }
                } else {
                    throw new CodecException("No codec present capable of encoding object [" + body + "] to content type [" + mediaType + "]");
                }
            }
        }
        responsePublisherCallback.accept(response);
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

}
