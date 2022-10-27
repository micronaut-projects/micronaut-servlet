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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.async.subscriber.CompletionAwareSubscriber;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.core.io.Writable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Status;
import io.micronaut.http.bind.binders.ContinuationArgumentBinder;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.http.context.event.HttpRequestReceivedEvent;
import io.micronaut.http.context.event.HttpRequestTerminatedEvent;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.filter.HttpFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.server.binding.RequestArgumentSatisfier;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import io.micronaut.http.server.types.files.FileCustomizableResponseType;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.http.server.types.files.SystemFile;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.web.router.MethodBasedRouteMatch;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRoute;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.exceptions.DuplicateRouteException;
import io.micronaut.web.router.exceptions.UnsatisfiedRouteException;
import io.micronaut.web.router.resource.StaticResourceResolver;
import io.netty.handler.codec.http.HttpHeaderValues;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.micronaut.core.util.KotlinUtils.isKotlinCoroutineSuspended;
import static io.micronaut.http.HttpAttributes.AVAILABLE_HTTP_METHODS;
import static io.micronaut.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static io.micronaut.inject.beans.KotlinExecutableMethodUtils.isKotlinFunctionReturnTypeUnit;

/**
 * An HTTP handler that can deal with Serverless requests.
 *
 * @param <Req> The request object
 * @param <Res> The response object
 * @author graemerocher
 * @since 1.2.0
 */
public abstract class ServletHttpHandler<Req, Res> implements AutoCloseable, LifeCycle<ServletHttpHandler<Req, Res>> {
    /**
     * Logger to be used by subclasses for logging.
     */
    protected static final Logger LOG = LoggerFactory.getLogger(ServletHttpHandler.class);

    private static final Pattern IGNORABLE_ERROR_MESSAGE = Pattern.compile(
            "^.*(?:connection.*(?:reset|closed|abort|broken)|broken.*pipe).*$", Pattern.CASE_INSENSITIVE);

    private final Router router;
    private final RequestArgumentSatisfier requestArgumentSatisfier;
    private final MediaTypeCodecRegistry mediaTypeCodecRegistry;
    private final ApplicationContext applicationContext;
    private final Map<Class<?>, ServletResponseEncoder<?>> responseEncoders;
    private final ErrorResponseProcessor errorResponseProcessor;
    private final StaticResourceResolver staticResourceResolver;

    /**
     * Default constructor.
     *
     * @param applicationContext The application context
     */
    public ServletHttpHandler(ApplicationContext applicationContext) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "The application context cannot be null");
        this.router = applicationContext.getBean(Router.class);
        this.requestArgumentSatisfier = applicationContext.getBean(RequestArgumentSatisfier.class);
        this.mediaTypeCodecRegistry = applicationContext.getBean(MediaTypeCodecRegistry.class);
        //noinspection unchecked
        this.responseEncoders = applicationContext.streamOfType(ServletResponseEncoder.class)
                .collect(Collectors.toMap(
                        ServletResponseEncoder::getResponseType,
                        (o) -> o
                ));
        this.errorResponseProcessor = applicationContext.getBean(ErrorResponseProcessor.class);
        this.staticResourceResolver = applicationContext.getBean(StaticResourceResolver.class);

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

    static boolean isPreflightRequest(HttpRequest<?> request) {
        HttpHeaders headers = request.getHeaders();
        Optional<String> origin = headers.getOrigin();
        return origin.isPresent() && headers.contains(ACCESS_CONTROL_REQUEST_METHOD) && HttpMethod.OPTIONS == request.getMethod();
    }

    /**
     * Handles a {@link DefaultServletExchange}.
     *
     * @param exchange The exchange
     */
    public void service(ServletExchange<Req, Res> exchange) {
        final long time = System.currentTimeMillis();
        try {
            final MutableHttpResponse<Object> res = exchange.getResponse();
            final HttpRequest<Object> req = exchange.getRequest();
            applicationContext.publishEvent(new HttpRequestReceivedEvent(req));

            final List<UriRouteMatch<Object, Object>> matchingRoutes = router.findAllClosest(req);

            boolean preflightRequest = isPreflightRequest(req);

            if (CollectionUtils.isEmpty(matchingRoutes) && preflightRequest) {

                List<UriRouteMatch<Object, Object>> anyUriRoutes = router.findAny(req.getUri().getPath(), req)
                    .collect(Collectors.toList());
                req.setAttribute(AVAILABLE_HTTP_METHODS, anyUriRoutes.stream().map(UriRouteMatch::getHttpMethod).collect(Collectors.toList()));
                if (anyUriRoutes.isEmpty()) {
                    handlePageNotFound(exchange, res, req);
                } else {
                    UriRouteMatch<Object, Object> establishedRoute = anyUriRoutes.get(0);
                    req.setAttribute(HttpAttributes.ROUTE, establishedRoute.getRoute());
                    req.setAttribute(HttpAttributes.ROUTE_MATCH, establishedRoute);
                    req.setAttribute(HttpAttributes.URI_TEMPLATE, establishedRoute.getRoute().getUriMatchTemplate().toString());
                    invokeRouteMatch(req, res, establishedRoute, false, true, exchange);
                }

            } else if (CollectionUtils.isNotEmpty(matchingRoutes)) {

                RouteMatch<Object> route;
                if (matchingRoutes.size() > 1) {
                    throw new DuplicateRouteException(req.getPath(), matchingRoutes);
                } else {
                    UriRouteMatch<Object, Object> establishedRoute = matchingRoutes.get(0);
                    req.setAttribute(HttpAttributes.ROUTE, establishedRoute.getRoute());
                    req.setAttribute(HttpAttributes.ROUTE_MATCH, establishedRoute);
                    req.setAttribute(HttpAttributes.URI_TEMPLATE, establishedRoute.getRoute().getUriMatchTemplate().toString());
                    route = establishedRoute;
                }


                if (LOG.isDebugEnabled()) {
                    LOG.debug("{} - {} - routed to controller {}", req.getMethodName(), req.getPath(), route.getDeclaringType().getSimpleName());
                    traceHeaders(req.getHeaders());
                }

                invokeRouteMatch(req, res, route, false, true, exchange);

            } else {

                if (LOG.isDebugEnabled()) {
                    LOG.debug("{} - {} - No matching routes found", req.getMethodName(), req.getPath());
                    traceHeaders(req.getHeaders());
                }

                Set<String> existingRouteMethods = router
                        .findAny(req.getUri().toString(), req)
                        .map(UriRouteMatch::getRoute)
                        .map(UriRoute::getHttpMethodName)
                        .collect(Collectors.toSet());

                if (CollectionUtils.isNotEmpty(existingRouteMethods)) {
                    if (existingRouteMethods.contains(req.getMethodName())) {
                        MediaType contentType = req.getContentType().orElse(null);
                        if (contentType != null) {
                            // must be invalid mime type
                            boolean invalidMediaType = router.findAny(req.getUri().toString(), req)
                                    .anyMatch(rm -> rm.doesConsume(contentType));
                            if (!invalidMediaType) {
                                handleStatusRoute(exchange, res, req, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                            } else {
                                handlePageNotFound(exchange, res, req);
                            }

                        } else {
                            handlePageNotFound(exchange, res, req);
                        }
                    } else {
                        final RouteMatch<Object> notAllowedRoute =
                                router.route(HttpStatus.METHOD_NOT_ALLOWED).orElse(null);

                        if (notAllowedRoute != null) {
                            invokeRouteMatch(req, res, notAllowedRoute, true, true, exchange);
                        } else {
                            handleStatusRoute(exchange, res, req, HttpStatus.METHOD_NOT_ALLOWED, () -> {
                                res.getHeaders().allowGeneric(existingRouteMethods);
                                res.status(HttpStatus.METHOD_NOT_ALLOWED);
                                return errorResponseProcessor.processResponse(ErrorContext.builder(req)
                                        .errorMessage("Method [" + req.getMethod() + "] not allowed for URI [" + req
                                                .getPath() + "]. Allowed methods: " + existingRouteMethods)
                                        .build(), res);
                            });
                        }
                    }
                } else {
                    final Optional<FileCustomizableResponseType> fileMatch = matchFile(req.getPath());

                    if (fileMatch.isPresent()) {
                        res.body(fileMatch.get());
                        if (exchange.getRequest().isAsyncSupported()) {
                            Flux.from(exchange.getRequest().subscribeOnExecutor(Mono.just(res)))
                                    .subscribe(response -> {
                                        encodeResponse(exchange, AnnotationMetadata.EMPTY_METADATA, response);
                                        if (LOG.isDebugEnabled()) {
                                            LOG.debug("Request [{} - {}] completed successfully", req.getMethodName(), req.getUri());
                                        }
                                    }, throwable -> LOG.error("Request [{} - {}] completed with error: {}", req.getMethodName(), req.getUri(), throwable.getMessage(), throwable));
                        } else {
                            try {
                                encodeResponse(exchange, AnnotationMetadata.EMPTY_METADATA, res);
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("Request [{} - {}] completed successfully", req.getMethodName(), req.getUri());
                                }
                            } catch (Exception e) {
                                LOG.error("Request [{} - {}] completed with error: {}", req.getMethodName(), req.getUri(), e.getMessage(), e);
                            }
                        }
                    } else {
                        handlePageNotFound(exchange, res, req);
                    }
                }
            }
        } finally {
            applicationContext.publishEvent(new HttpRequestTerminatedEvent(exchange.getRequest()));
            if (LOG.isTraceEnabled()) {
                final HttpRequest<? super Object> r = exchange.getRequest();
                LOG.trace("Executed HTTP Request [{} {}] in: {}ms",
                        r.getMethod(),
                        r.getPath(),
                        (System.currentTimeMillis() - time)
                );
            }
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

    private void handlePageNotFound(ServletExchange<Req, Res> exchange, MutableHttpResponse<Object> res, HttpRequest<Object> req) {
        handleStatusRoute(exchange, res, req, HttpStatus.NOT_FOUND);
    }

    private void handleStatusRoute(ServletExchange<Req, Res> exchange, MutableHttpResponse<Object> res, HttpRequest<Object> req, HttpStatus httpStatus) {
        handleStatusRoute(exchange, res, req, httpStatus, () -> {
            res.status(httpStatus);
            return errorResponseProcessor.processResponse(ErrorContext.builder(req).build(), res);
        });
    }

    private void handleStatusRoute(ServletExchange<Req, Res> exchange, MutableHttpResponse<Object> res, HttpRequest<Object> req, HttpStatus httpStatus,
                                   Callable<MutableHttpResponse<?>> defaultProcessor) {

        final RouteMatch<Object> statusRoute = router.route(httpStatus).orElse(null);
        if (statusRoute != null) {
            invokeRouteMatch(req, res, statusRoute, true, true, exchange);
        } else {
            Publisher<MutableHttpResponse<?>> responsePublisher = filterPublisher(exchange, new AtomicReference<>(req), Mono.fromCallable(defaultProcessor), null);
            subscribeToResponsePublisher(req, res, null, false, exchange, responsePublisher, AnnotationMetadata.EMPTY_METADATA);
        }
    }

    private Publisher<MutableHttpResponse<?>> handleStatusException(ServletExchange<Req, Res> exchange,
                                                                    MutableHttpResponse<?> response,
                                                                    AtomicReference<HttpRequest<?>> requestReference) {
        HttpStatus status = response.status();
        Optional<RouteMatch> route = response.getAttribute(HttpAttributes.ROUTE_MATCH, RouteMatch.class);
        if (route.isPresent()) {
            boolean isErrorRoute = route.filter(RouteMatch::isErrorRoute).isPresent();
            if (!isErrorRoute && status.getCode() >= 400) {
                //overwrite any previously set status so the route `@Status` can apply

                final RouteMatch<Object> errorRoute = lookupStatusRoute(route.get(), status);
                if (errorRoute != null) {
                    exchange.getResponse().status(HttpStatus.OK);
                    return buildResponsePublisher(
                            exchange,
                            requestReference.get(),
                            errorRoute
                    );
                }
            }
        }
        return Flux.just(response);
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

    private void invokeRouteMatch(
            HttpRequest<Object> req,
            MutableHttpResponse<Object> res,
            final RouteMatch<?> route,
            boolean isErrorRoute,
            boolean executeFilters,
            ServletExchange<Req, Res> exchange) {

        AtomicReference<HttpRequest<?>> requestReference = new AtomicReference<>(req);
        Publisher<MutableHttpResponse<?>> responsePublisher = buildResponsePublisher(exchange, req, route)
                .flatMap(response -> {
                    return handleStatusException(exchange, response, requestReference);
                })
                .onErrorResume(t -> {
                    final HttpRequest httpRequest = requestReference.get();
                    return handleException(httpRequest, exchange.getResponse(), route, false, t, exchange);
                });

        if (executeFilters) {
            responsePublisher = filterPublisher(exchange, new AtomicReference<>(req), responsePublisher, route);
        }
        final AnnotationMetadata annotationMetadata = route.getAnnotationMetadata();
        subscribeToResponsePublisher(req, res, route, isErrorRoute, exchange, responsePublisher, annotationMetadata);
    }

    private void subscribeToResponsePublisher(HttpRequest<Object> req,
                                              MutableHttpResponse<Object> res,
                                              RouteMatch<?> route,
                                              boolean isErrorRoute,
                                              ServletExchange<Req, Res> exchange,
                                              Publisher<? extends MutableHttpResponse<?>> responsePublisher,
                                              AnnotationMetadata annotationMetadata) {
        final ServletHttpRequest<Req, ? super Object> exchangeRequest = exchange.getRequest();
        boolean isAsyncSupported = exchangeRequest.isAsyncSupported();
        final Flux<? extends MutableHttpResponse<?>> responseFlux = Flux.from(responsePublisher)
                .flatMap(response -> {
                    Object body = response.body();

                    if (body != null) {
                        if (Publishers.isConvertibleToPublisher(body)) {
                            boolean isSingle = Publishers.isSingle(body.getClass());
                            if (isSingle) {
                                Flux<?> flux = Flux.from(Publishers.convertPublisher(body, Publisher.class));
                                return flux.map((Function<Object, MutableHttpResponse<?>>) o -> {
                                    if (o instanceof HttpResponse) {
                                        return res;
                                    } else {
                                        ServletHttpResponse<Res, ? super Object> res1 = exchange.getResponse();
                                        res1.body(o);
                                        return res1;
                                    }
                                });
                            } else {
                                // stream case
                                Publisher<?> bodyPublisher = Publishers.convertPublisher(body, Publisher.class);
                                final ServletHttpResponse<Res, ? super Object> servletResponse = exchange.getResponse();
                                if (isAsyncSupported) {
                                    servletResponse.body(servletResponse.stream(bodyPublisher));
                                } else {
                                    // fallback to blocking
                                    servletResponse.body(Flux.from(bodyPublisher).collectList().block());
                                }
                                return Flux.just(servletResponse);
                            }
                        }
                    }

                    return Mono.just(response);
                }).onErrorResume(throwable ->
                        handleException(req, res, route, isErrorRoute, throwable, exchange));

        if (isAsyncSupported) {
            //noinspection ResultOfMethodCallIgnored
            Flux.from(exchangeRequest.subscribeOnExecutor(responseFlux))
                    .subscribe(response -> {
                        encodeResponse(exchange, annotationMetadata, response);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Request [{} - {}] completed successfully", req.getMethodName(), req.getUri());
                        }
                    }, throwable -> LOG.error("Request [{} - {}] completed with error: {}", req.getMethodName(), req.getUri(), throwable.getMessage(), throwable));
        } else {
            responseFlux
                    .subscribeOn(Schedulers.immediate())
                    .subscribe(response -> {
                        encodeResponse(exchange, annotationMetadata, response);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Request [{} - {}] completed successfully", req.getMethodName(), req.getUri());
                        }
                    }, throwable -> LOG.error("Request [{} - {}] completed with error: {}", req.getMethodName(), req.getUri(), throwable.getMessage(), throwable));
        }
    }

    private MutableHttpResponse<?> toMutableResponse(ServletExchange<Req, Res> exchange, HttpResponse<?> message) {
        MutableHttpResponse<?> mutableHttpResponse;
        if (message instanceof MutableHttpResponse) {
            mutableHttpResponse = (MutableHttpResponse<?>) message;
        } else {
            HttpStatus httpStatus = message.status();
            mutableHttpResponse = exchange.getResponse().status(httpStatus, httpStatus.getReason());
            mutableHttpResponse.body(message.body());
            message.getHeaders().forEach((name, value) -> {
                for (String val: value) {
                    mutableHttpResponse.header(name, val);
                }
            });
            mutableHttpResponse.getAttributes().putAll(message.getAttributes());
        }
        return mutableHttpResponse;
    }

    private boolean isSingle(RouteMatch<?> finalRoute, Class<?> bodyClass) {
        return finalRoute.isSpecifiedSingle() || (finalRoute.isSingleResult() &&
                (finalRoute.isAsync() || finalRoute.isSuspended() || Publishers.isSingle(bodyClass)));
    }

    private MutableHttpResponse<Object> forStatus(ServletExchange<Req, Res> exchange, RouteMatch routeMatch) {
        return forStatus(exchange, routeMatch, HttpStatus.OK);
    }

    private MutableHttpResponse<Object> forStatus(ServletExchange<Req, Res> exchange, RouteMatch routeMatch, HttpStatus defaultStatus) {
        HttpStatus status = routeMatch.findStatus(defaultStatus);
        final ServletHttpResponse<Res, ? super Object> response = exchange.getResponse();
        // Unfortunately it's impossible to tell if the status is OK because its the
        // default or because it was explicitly set
        if (response.status() == null || response.status() == HttpStatus.OK) {
            return response.status(status);
        } else {
            return response;
        }
    }

    private MutableHttpResponse<?> newNotFoundError(ServletExchange<Req, Res> exchange, HttpRequest<?> request) {
        return errorResponseProcessor.processResponse(
                ErrorContext.builder(request)
                        .errorMessage("Page Not Found")
                        .build(), exchange.getResponse().status(HttpStatus.NOT_FOUND));
    }

    private Flux<MutableHttpResponse<?>> buildResponsePublisher(
            ServletExchange<Req, Res> exchange,
            HttpRequest<?> req,
            RouteMatch<?> route
    ) {
        return Flux.deferContextual(contextView -> {
            return Flux.create((subscriber) -> {
                RouteMatch<?> computedRoute = route;
                if (!computedRoute.isExecutable()) {
                    computedRoute = requestArgumentSatisfier.fulfillArgumentRequirements(
                            computedRoute,
                            req,
                            false
                    );
                }
                if (!computedRoute.isExecutable() && HttpMethod.permitsRequestBody(req.getMethod()) && !computedRoute.getBodyArgument().isPresent()) {
                    final ConvertibleValues<?> convertibleValues = req.getBody(ConvertibleValues.class).orElse(null);
                    if (convertibleValues != null) {

                        final Collection<Argument> requiredArguments = route.getRequiredArguments();
                        Map<String, Object> newValues = new HashMap<>(requiredArguments.size());
                        for (Argument<?> requiredArgument : requiredArguments) {
                            final String name = requiredArgument.getName();
                            convertibleValues.get(name, requiredArgument).ifPresent(v -> newValues.put(name, v));
                        }
                        if (CollectionUtils.isNotEmpty(newValues)) {
                            computedRoute = computedRoute.fulfill(
                                    newValues
                            );
                        }
                    }
                }

                RouteMatch<?> finalRoute = computedRoute;
                if (finalRoute.isSuspended()) {
                    ContinuationArgumentBinder.setupCoroutineContext(req, contextView);
                }
                Object result = null;
                try {
                    result = ServerRequestContext.with(req, (Callable<Object>) finalRoute::execute);
                } catch (Throwable t) {
                    subscriber.error(t);
                    return;
                }
                if (result instanceof Optional) {
                    result = ((Optional<?>) result).orElse(null);
                }
                MutableHttpResponse<?> outgoingResponse;

                if (result == null) {
                    if (finalRoute.isVoid()) {
                        outgoingResponse = forStatus(exchange, finalRoute);
                        if (HttpMethod.permitsRequestBody(req.getMethod())) {
                            outgoingResponse.header(HttpHeaders.CONTENT_LENGTH, HttpHeaderValues.ZERO);
                        }
                    } else {
                        outgoingResponse = newNotFoundError(exchange, req);
                    }
                } else {
                    HttpStatus defaultHttpStatus = finalRoute.isErrorRoute() ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK;
                    boolean isReactive = finalRoute.isAsyncOrReactive() || Publishers.isConvertibleToPublisher(result);
                    if (isReactive) {
                        Class<?> bodyClass = result.getClass();
                        boolean isSingle = isSingle(finalRoute, bodyClass);
                        boolean isCompletable = !isSingle && finalRoute.isVoid() && Publishers.isCompletable(bodyClass);
                        if (isSingle || isCompletable) {
                            // full response case
                            Publisher<Object> publisher = Publishers.convertPublisher(result, Publisher.class);
                            Publishers.mapOrSupplyEmpty(publisher, new Publishers.MapOrSupplyEmpty<Object, MutableHttpResponse<?>>() {
                                @Override
                                public MutableHttpResponse<?>  map(Object o) {
                                    MutableHttpResponse<?> singleResponse;
                                    if (o instanceof Optional) {
                                        Optional optional = (Optional) o;
                                        if (optional.isPresent()) {
                                            o = ((Optional<?>) o).get();
                                        } else {
                                            return supplyEmpty();
                                        }
                                    }
                                    if (o instanceof HttpResponse) {
                                        singleResponse = toMutableResponse(exchange, (HttpResponse<?>) o);
                                    } else if (o instanceof HttpStatus) {
                                        singleResponse = forStatus(exchange, finalRoute, (HttpStatus) o);
                                    } else {
                                        singleResponse = forStatus(exchange, finalRoute, defaultHttpStatus)
                                                .body(o);
                                    }
                                    singleResponse.setAttribute(HttpAttributes.ROUTE_MATCH, finalRoute);
                                    return singleResponse;
                                }

                                @Override
                                public MutableHttpResponse<?> supplyEmpty() {
                                    MutableHttpResponse<?> singleResponse;
                                    if (isCompletable || finalRoute.isVoid()) {
                                        singleResponse = forStatus(exchange, finalRoute, HttpStatus.OK)
                                                .header(HttpHeaders.CONTENT_LENGTH, HttpHeaderValues.ZERO);
                                    } else {
                                        singleResponse = newNotFoundError(exchange, req);
                                    }
                                    singleResponse.setAttribute(HttpAttributes.ROUTE_MATCH, finalRoute);
                                    return singleResponse;
                                }

                            }).subscribe(new CompletionAwareSubscriber<MutableHttpResponse<?>>() {

                                @Override
                                public void doOnSubscribe(Subscription s) {
                                    s.request(1);
                                }

                                @Override
                                public void doOnNext(MutableHttpResponse<?> mutableHttpResponse) {
                                    subscriber.next(mutableHttpResponse);
                                }

                                @Override
                                public void doOnError(Throwable t) {
                                    subscriber.error(t);
                                }

                                @Override
                                public void doOnComplete() {
                                    subscriber.complete();
                                }
                            });
                            return;
                        }
                    }
                    // now we have the raw result, transform it as necessary
                    if (result instanceof HttpStatus) {
                        outgoingResponse = exchange.getResponse().status((HttpStatus) result);
                    } else {
                        boolean isSuspended = finalRoute.isSuspended();
                        if (isSuspended) {
                            boolean isKotlinFunctionReturnTypeUnit =
                                    finalRoute instanceof MethodBasedRouteMatch &&
                                            isKotlinFunctionReturnTypeUnit(((MethodBasedRouteMatch) finalRoute).getExecutableMethod());
                            final Supplier<CompletableFuture<?>> supplier = ContinuationArgumentBinder.extractContinuationCompletableFutureSupplier(req);
                            if (isKotlinCoroutineSuspended(result)) {
                                CompletableFuture<?> f = supplier.get();
                                f.whenComplete((o, throwable) -> {
                                    if (throwable != null) {
                                        subscriber.error(throwable);
                                    } else {
                                        if (o == null) {
                                            subscriber.next(newNotFoundError(exchange, req));
                                        } else {
                                            MutableHttpResponse<?> response;
                                            if (o instanceof HttpResponse) {
                                                response = toMutableResponse(exchange, (HttpResponse<?>) o);
                                            } else {
                                                response = forStatus(exchange, finalRoute, defaultHttpStatus);
                                                if (!isKotlinFunctionReturnTypeUnit) {
                                                    response = response.body(o);
                                                }
                                            }
                                            response.setAttribute(HttpAttributes.ROUTE_MATCH, finalRoute);
                                            subscriber.next(response);
                                        }
                                        subscriber.complete();
                                    }
                                });
                                return;
                            } else {
                                Object suspendedBody;
                                if (isKotlinFunctionReturnTypeUnit) {
                                    suspendedBody = Mono.empty();
                                } else {
                                    suspendedBody = result;
                                }
                                if (suspendedBody instanceof HttpResponse) {
                                    outgoingResponse = toMutableResponse(exchange, (HttpResponse<?>) suspendedBody);
                                } else {
                                    outgoingResponse = forStatus(exchange, finalRoute, defaultHttpStatus)
                                            .body(suspendedBody);
                                }
                            }

                        } else {
                            if (result instanceof HttpResponse) {
                                outgoingResponse = toMutableResponse(exchange, (HttpResponse<?>) result);
                            } else {
                                outgoingResponse = forStatus(exchange, finalRoute, defaultHttpStatus)
                                        .body(result);
                            }
                        }
                    }

                    // for head request we never emit the body
                    if (req != null && req.getMethod().equals(HttpMethod.HEAD)) {
                        outgoingResponse.body(null);
                    }
                }
                outgoingResponse.setAttribute(HttpAttributes.ROUTE_MATCH, finalRoute);
                subscriber.next(outgoingResponse);
                subscriber.complete();
            });
        });
    }

    private void encodeResponse(ServletExchange<Req, Res> exchange,
                                AnnotationMetadata annotationMetadata,
                                HttpResponse<?> response) {
        final Object body = response.getBody().orElse(null);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Sending response {}", response.status());
            traceHeaders(response.getHeaders());
        }

        if (body != null) {
            Class<?> bodyType = body.getClass();
            ServletResponseEncoder<Object> responseEncoder = (ServletResponseEncoder<Object>) responseEncoders.get(bodyType);
            if (responseEncoder != null) {
                // NOTE[moss]: blockLast() here *was* subscribe(), but that returns immediately, which was
                // sometimes allowing the main response publisher to complete before this responseEncoder
                // could fill out the response! Blocking here will ensure that the response is filled out
                // before the main response publisher completes. This will be improved later to avoid the block.

                Flux.from(responseEncoder.encode(exchange, annotationMetadata, body)).blockLast();
                return;
            }

            setHeadersFromMetadata(exchange.getResponse(), annotationMetadata, body);

            if (body instanceof Publisher) {
                Flux.from((Publisher<?>) body).blockLast();
            } else if (body instanceof HttpStatus) {
                exchange.getResponse().status((HttpStatus) body);
            } else if (body instanceof CharSequence) {
                if (response instanceof MutableHttpResponse) {
                    if (!response.getContentType().isPresent()) {
                        ((MutableHttpResponse<?>) response).contentType(MediaType.APPLICATION_JSON);
                    }
                }
                try (BufferedWriter writer = exchange.getResponse().getWriter()) {
                    writer.write(body.toString());
                    writer.flush();
                } catch (IOException e) {
                    throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                }
            } else if (body instanceof byte[]) {
                try (OutputStream outputStream = exchange.getResponse().getOutputStream()) {
                    outputStream.write((byte[]) body);
                    outputStream.flush();
                } catch (IOException e) {
                    throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                }
            } else if (body instanceof Writable) {
                Writable writable = (Writable) body;
                try (OutputStream outputStream = exchange.getResponse().getOutputStream()) {
                    writable.writeTo(outputStream, response.getCharacterEncoding());
                    outputStream.flush();
                } catch (IOException e) {
                    throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                }
            } else {
                final MediaType ct = response.getContentType().orElseGet(() -> {
                    final Produces ann = bodyType.getAnnotation(Produces.class);
                    if (ann != null) {
                        final String[] v = ann.value();
                        if (ArrayUtils.isNotEmpty(v)) {
                            final MediaType mediaType = new MediaType(v[0]);
                            if (response instanceof MutableHttpResponse) {
                                ((MutableHttpResponse<?>) response).contentType(mediaType);
                            }
                            return mediaType;
                        }
                    }
                    if (response instanceof MutableHttpResponse) {
                        ((MutableHttpResponse<?>) response).contentType(MediaType.APPLICATION_JSON_TYPE);
                    }
                    return MediaType.APPLICATION_JSON_TYPE;
                });
                final MediaTypeCodec codec = mediaTypeCodecRegistry.findCodec(ct, bodyType).orElse(null);
                if (codec != null) {
                    try (OutputStream outputStream = exchange.getResponse().getOutputStream()) {
                        codec.encode(body, outputStream);
                        outputStream.flush();
                    } catch (Throwable e) {
                        throw new CodecException("Failed to encode object [" + body + "] to content type [" + ct + "]: " + e.getMessage(), e);
                    }
                } else {
                    throw new CodecException("No codec present capable of encoding object [" + body + "] to content type [" + ct + "]");
                }
            }
        }
    }

    private void setHeadersFromMetadata(MutableHttpResponse<Object> res, AnnotationMetadata annotationMetadata, Object result) {
        if (!res.getContentType().isPresent()) {
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

    private void logException(Throwable cause) {
        //handling connection reset by peer exceptions
        if (isIgnorable(cause)) {
            logIgnoredException(cause);
        } else {
            if (LOG.isErrorEnabled()) {
                LOG.error("Unexpected error occurred: " + cause.getMessage(), cause);
            }
        }
    }

    private boolean isIgnorable(Throwable cause) {
        String message = cause.getMessage();
        return cause instanceof IOException && message != null && IGNORABLE_ERROR_MESSAGE.matcher(message).matches();
    }

    private void logIgnoredException(Throwable cause) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Swallowed an IOException caused by client connectivity: " + cause.getMessage(), cause);
        }
    }

    private Publisher<MutableHttpResponse<?>> handleException(
            HttpRequest<Object> req,
            MutableHttpResponse<Object> res,
            RouteMatch<?> route,
            boolean isErrorRoute,
            Throwable e,
            ServletExchange<Req, Res> exchange) {
        req.setAttribute(HttpAttributes.ERROR, e);
        //overwrite any previously set status so the route `@Status` can apply
        exchange.getResponse().status(HttpStatus.OK);
        if (isErrorRoute) {
            // handle error default
            if (LOG.isErrorEnabled()) {
                LOG.error("Error occurred executing Error route [" + route + "]: " + e.getMessage(), e);
            }
            return Flux.just(res.status(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage()));
        } else {
            if (e instanceof UnsatisfiedRouteException || e instanceof ConversionErrorException) {
                final RouteMatch<Object> badRequestRoute = lookupStatusRoute(route, HttpStatus.BAD_REQUEST);
                if (badRequestRoute != null) {
                    return buildResponsePublisher(exchange, req, badRequestRoute);
                } else {
                    return invokeExceptionHandlerIfPossible(req, e, exchange);
                }
            } else if (e instanceof HttpStatusException) {
                HttpStatusException statusException = (HttpStatusException) e;
                final HttpStatus status = statusException.getStatus();
                final int code = status.getCode();
                final boolean isErrorStatus = code >= 400;
                final RouteMatch<Object> statusRoute = isErrorStatus ? lookupStatusRoute(route, status) : null;
                if (statusRoute != null) {
                    return buildResponsePublisher(exchange, req, statusRoute);
                } else {
                    MutableHttpResponse<Object> response = res.status(code, statusException.getMessage());
                    final Object body = statusException.getBody().orElse(null);
                    if (body != null) {
                        response.body(body);
                    } else if (isErrorStatus) {
                        response = errorResponseProcessor.processResponse(ErrorContext.builder(req)
                                .errorMessage(statusException.getMessage())
                                .build(), response);
                    }
                    return Flux.just(response);
                }
            } else {
                RouteMatch<Object> errorRoute = lookupErrorRoute(route, e);
                if (errorRoute == null) {
                    if (e instanceof CodecException) {
                        Throwable cause = e.getCause();
                        if (cause != null) {
                            errorRoute = lookupErrorRoute(route, cause);
                        }
                        if (errorRoute == null) {
                            final RouteMatch<Object> badRequestRoute = lookupStatusRoute(route, HttpStatus.BAD_REQUEST);
                            if (badRequestRoute != null) {
                                return buildResponsePublisher(exchange, req, badRequestRoute);
                            } else {
                                return invokeExceptionHandlerIfPossible(req, e, exchange, HttpStatus.BAD_REQUEST);
                            }
                        }
                    }
                }
                if (errorRoute != null) {
                    return buildResponsePublisher(exchange, req, errorRoute);
                } else {
                    return invokeExceptionHandlerIfPossible(req, e, exchange);
                }
            }
        }
    }

    private MutableHttpResponse<?> errorResultToResponse(ServletExchange<Req, Res> exchange, Object result, HttpStatus defaultStatus) {
        MutableHttpResponse<?> response;
        if (result instanceof HttpResponse) {
            return toMutableResponse(exchange, (HttpResponse<?>) result);
        } else {
            if (result instanceof HttpStatus) {
                response = exchange.getResponse().status((HttpStatus) result);
            } else {
                response = exchange.getResponse().status(defaultStatus).body(result);
            }
        }
        return response;
    }

    private Publisher<MutableHttpResponse<?>> createDefaultErrorResponsePublisher(ServletExchange<Req, Res> exchange,
                                                                                  HttpRequest<?> request,
                                                                                  Throwable cause,
                                                                                  HttpStatus defaultStatus) {
        return Publishers.just(createDefaultErrorResponse(exchange, request, cause, defaultStatus));
    }

    private MutableHttpResponse<?> createDefaultErrorResponse(ServletExchange<Req, Res> exchange,
                                                              HttpRequest<?> request,
                                                              Throwable cause,
                                                              HttpStatus defaultStatus) {
        logException(cause);
        HttpStatus status = defaultStatus != null ? defaultStatus : HttpStatus.INTERNAL_SERVER_ERROR;
        final MutableHttpResponse<Object> response = exchange.getResponse().status(status);
        response.setAttribute(HttpAttributes.EXCEPTION, cause);
        return errorResponseProcessor.processResponse(
                ErrorContext.builder(request)
                        .cause(cause)
                        .errorMessage("Internal Server Error: " + cause.getMessage())
                        .build(), response);
    }

    private Publisher<MutableHttpResponse<?>> invokeExceptionHandlerIfPossible(
            HttpRequest<Object> req,
            Throwable e,
            ServletExchange<Req, Res> exchange) {
        return invokeExceptionHandlerIfPossible(req, e, exchange, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private Publisher<MutableHttpResponse<?>> invokeExceptionHandlerIfPossible(
            HttpRequest<Object> req,
            Throwable e,
            ServletExchange<Req, Res> exchange,
            HttpStatus defaultStatus) {

        final Class<? extends Throwable> type = e.getClass();
        final ExceptionHandler<Throwable, ?> exceptionHandler = applicationContext.findBean(ExceptionHandler.class, Qualifiers.byTypeArgumentsClosest(type, Object.class))
                .orElse(null);

        if (exceptionHandler != null) {
            try {
                return ServerRequestContext.with(req, (Supplier<? extends Publisher<MutableHttpResponse<?>>>) () -> {
                    final Object result = exceptionHandler.handle(req, e);
                    MutableHttpResponse<?> resp = errorResultToResponse(exchange, result, defaultStatus);
                    resp.setAttribute(HttpAttributes.ROUTE_MATCH, null);
                    return Flux.just(resp);
                });
            } catch (Throwable ex) {
                if (LOG.isErrorEnabled()) {
                    LOG.error("Error occurred executing exception handler [" + exceptionHandler.getClass() + "]: " + e.getMessage(), ex);
                }
                return createDefaultErrorResponsePublisher(exchange, req, ex, defaultStatus);
            }
        } else {
            return createDefaultErrorResponsePublisher(exchange, req, e, defaultStatus);
        }
    }

    private RouteMatch<Object> lookupErrorRoute(RouteMatch<?> route, Throwable e) {
        if (route == null) {
            return router.route(e).orElse(null);
        } else {
            return router.route(route.getDeclaringType(), e)
                    .orElseGet(() -> router.route(e).orElse(null));
        }
    }

    private RouteMatch<Object> lookupStatusRoute(RouteMatch<?> route, HttpStatus status) {
        if (route == null) {
            return router.route(status).orElse(null);
        } else {
            return router.route(route.getDeclaringType(), status)
                    .orElseGet(() ->
                                       router.route(status).orElse(null)
                    );
        }
    }

    private Publisher<MutableHttpResponse<?>> filterPublisher(
            ServletExchange<Req, Res> exchange,
            AtomicReference<io.micronaut.http.HttpRequest<?>> requestReference,
            Publisher<MutableHttpResponse<?>> routePublisher,
            RouteMatch<?> routeMatch
    ) {
        List<HttpFilter> filters = new ArrayList<>(router.findFilters(requestReference.get()));
        if (filters.isEmpty()) {
            return routePublisher;
        }

        final Function<MutableHttpResponse<?>, Publisher<MutableHttpResponse<?>>> checkForStatus = (response) -> {
            return handleStatusException(exchange, response, requestReference);
        };

        final Function<Throwable, Publisher<MutableHttpResponse<?>>> onError = (t) -> {
            final HttpRequest httpRequest = requestReference.get();
            return handleException(httpRequest, exchange.getResponse(), routeMatch, false, t, exchange);
        };

        AtomicInteger integer = new AtomicInteger();
        int len = filters.size();
        ServerFilterChain filterChain = new ServerFilterChain() {
            @SuppressWarnings("unchecked")
            @Override
            public Publisher<MutableHttpResponse<?>> proceed(io.micronaut.http.HttpRequest<?> request) {
                int pos = integer.incrementAndGet();
                if (pos > len) {
                    throw new IllegalStateException("The FilterChain.proceed(..) method should be invoked exactly once per filter execution. The method has instead been invoked multiple times by an erroneous filter definition.");
                }
                if (pos == len) {
                    return routePublisher;
                }
                HttpFilter httpFilter = filters.get(pos);
                return Flux.from((Publisher<MutableHttpResponse<?>>) httpFilter.doFilter(requestReference.getAndSet(request), this))
                        .flatMap(checkForStatus)
                        .onErrorResume(onError);
            }
        };
        HttpFilter httpFilter = filters.get(0);
        final HttpRequest<?> req = requestReference.get();
        Publisher<MutableHttpResponse<?>> resultingPublisher =
                ServerRequestContext.with(
                        req,
                        (Supplier<Publisher<MutableHttpResponse<?>>>) () ->
                                Flux.from((Publisher<MutableHttpResponse<?>>) httpFilter.doFilter(req, filterChain))
                                        .flatMap(checkForStatus)
                                        .onErrorResume(onError)
                );

        //noinspection unchecked
        return resultingPublisher;
    }
}
