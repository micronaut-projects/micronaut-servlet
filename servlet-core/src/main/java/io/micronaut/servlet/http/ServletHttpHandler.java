package io.micronaut.servlet.http;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.core.io.Writable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.*;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Status;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.filter.HttpFilter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.OncePerRequestHttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.hateoas.JsonError;
import io.micronaut.http.server.binding.RequestArgumentSatisfier;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRoute;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.exceptions.DuplicateRouteException;
import io.micronaut.web.router.exceptions.UnsatisfiedRouteException;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * An HTTP handler that can deal with Serverless requests.
 *
 * @param <Req> The request object
 * @param <Res> The response object
 * @author graemerocher
 * @since 1.2.0
 */
public abstract class ServletHttpHandler<Req, Res> implements AutoCloseable {
    /**
     * Logger to be used by subclasses for logging.
     */
    static final Logger LOG = LoggerFactory.getLogger(ServletHttpHandler.class);

    private final Router router;
    private final RequestArgumentSatisfier requestArgumentSatisfier;
    private final MediaTypeCodecRegistry mediaTypeCodecRegistry;
    private final ApplicationContext applicationContext;

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
        try {

            ServletExchange<Req, Res> exchange = createExchange(request, response);
            service(exchange);
        } finally {
            applicationContext.close();
        }
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
            final List<UriRouteMatch<Object, Object>> matchingRoutes = router.findAllClosest(req);
            if (CollectionUtils.isNotEmpty(matchingRoutes)) {
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
                    LOG.debug("Matched route {} - {} to controller {}", req.getMethodName(), req.getPath(), route.getDeclaringType());
                }

                invokeRouteMatch(req, res, route, false, exchange);

            } else {
                Set<String> existingRouteMethods = router
                        .findAny(req.getUri().toString(), req)
                        .map(UriRouteMatch::getRoute)
                        .map(UriRoute::getHttpMethodName)
                        .collect(Collectors.toSet());

                if (CollectionUtils.isNotEmpty(existingRouteMethods)) {
                    final RouteMatch<Object> notAllowedRoute =
                            router.route(HttpStatus.METHOD_NOT_ALLOWED).orElse(null);

                    if (notAllowedRoute != null) {
                        invokeRouteMatch(req, res, notAllowedRoute, true, exchange);
                    } else {
                        res.getHeaders().allowGeneric(existingRouteMethods);
                        res.status(HttpStatus.METHOD_NOT_ALLOWED)
                            .body(new JsonError(
                                    "Method [" + req.getMethod() + "] not allowed for URI [" + req.getPath() + "]. Allowed methods: " + existingRouteMethods
                            ));
                        encodeResponse(exchange, res);
                    }
                } else {
                    final RouteMatch<Object> notFoundRoute =
                            router.route(HttpStatus.NOT_FOUND).orElse(null);

                    if (notFoundRoute != null) {
                        invokeRouteMatch(req, res, notFoundRoute, true, exchange);
                    } else {
                        res.status(HttpStatus.NOT_FOUND).body(new JsonError("Page Not Found"));
                        encodeResponse(exchange, res);
                    }
                }


            }
        } finally {
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

    @Override
    public void close() {
        if (applicationContext.isRunning()) {
            applicationContext.close();
        }
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
            ServletExchange<Req, Res> exchange) {

        try {

            Publisher<? extends MutableHttpResponse<?>> responsePublisher = buildResponsePublisher(req, res, route, isErrorRoute);

            final ServletHttpRequest<Req, ? super Object> exchangeRequest = exchange.getRequest();
            if (exchangeRequest.isAsyncSupported()) {
                final Flowable<? extends MutableHttpResponse<?>> responseFlowable = Flowable.fromPublisher(responsePublisher)
                        .flatMap(response -> {
                    final HttpStatus status = response.status();
                    if (!isErrorRoute && status.getCode() >= 400) {
                        final RouteMatch<Object> errorRoute = lookupStatusRoute(route, status);
                        if (errorRoute != null) {
                            return buildResponsePublisher(
                                    req,
                                    (MutableHttpResponse<Object>) response,
                                    errorRoute,
                                    true
                            );
                        }
                    }
                    encodeResponse(exchange, response);
                    return Publishers.just(response);
                }).onErrorResumeNext(throwable -> {
                    handleException(req, res, route, isErrorRoute, throwable, exchange);
                    return Flowable.error(throwable);
                });
                //noinspection ResultOfMethodCallIgnored
                Flowable.fromPublisher(exchangeRequest.subscribeOnExecutor(responseFlowable))
                        .subscribe(response -> {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Request [{} - {}] completed successfully", req.getMethodName(), req.getUri());
                            }
                        }, throwable -> {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Request [" + req.getMethodName() + " - " + req.getUri() + "] completed with error: " + throwable.getMessage(), throwable);
                            }
                        });

            } else {
                Flowable.fromPublisher(responsePublisher)
                        .blockingSubscribe(response -> {
                            final HttpStatus status = response.status();
                            if (!isErrorRoute && status.getCode() >= 400) {
                                final RouteMatch<Object> errorRoute = lookupStatusRoute(route, status);
                                if (errorRoute != null) {
                                    invokeRouteMatch(req, res, errorRoute, true, exchange);
                                }
                            } else {
                                encodeResponse(exchange, response);
                            }
                        }, error -> handleException(req, res, route, isErrorRoute, error, exchange));
            }
        } catch (Throwable e) {
            handleException(req, res, route, isErrorRoute, e, exchange);
        }
    }

    private Publisher<? extends MutableHttpResponse<?>> buildResponsePublisher(
            HttpRequest<Object> req,
            MutableHttpResponse<Object> res,
            RouteMatch<?> route,
            boolean isErrorRoute) {
        Publisher<? extends MutableHttpResponse<?>> responsePublisher
                = Flowable.defer(() -> {
            RouteMatch<?> computedRoute = route;
            final AnnotationMetadata annotationMetadata = computedRoute.getAnnotationMetadata();
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
                    for (Argument requiredArgument : requiredArguments) {
                        final String name = requiredArgument.getName();
                        final Object v = convertibleValues.get(name, requiredArgument).orElse(null);
                        if (v != null) {
                            newValues.put(name, v);
                        }
                    }
                    if (CollectionUtils.isNotEmpty(newValues)) {
                        computedRoute = computedRoute.fulfill(
                                newValues
                        );
                    }
                }
            }

            RouteMatch<?> finalComputedRoute = computedRoute;
            Object result = ServerRequestContext.with(req, (Callable<Object>) finalComputedRoute::execute);
            if (result instanceof Optional) {
                result = ((Optional) result).orElse(null);
            }
            if (result == null) {
                final ReturnType<?> returnType = computedRoute.getReturnType();
                final Argument<?> genericReturnType = returnType.asArgument();
                final Class<?> javaReturnType = returnType.getType();
                boolean isVoid = javaReturnType == void.class ||
                        Completable.class.isAssignableFrom(javaReturnType) ||
                        (genericReturnType.getFirstTypeVariable()
                                .map(arg -> arg.getType() == Void.class).orElse(false));
                if (isVoid) {
                    setHeadersFromMetadata(res, annotationMetadata, result);
                    return Publishers.just(res);
                } else {
                    return Publishers.just(HttpResponse.notFound());
                }
            }

            setHeadersFromMetadata(res, annotationMetadata, result);

            if (result instanceof Future) {
                Flowable<?> responseEmitter;
                if (result instanceof CompletionStage) {
                    CompletionStage<?> cs = (CompletionStage) result;
                    responseEmitter = Flowable.create(emitter -> cs.whenComplete((o, throwable) -> {
                        if (throwable != null) {
                            emitter.onError(throwable);
                        } else {
                            if (o != null) {
                                emitter.onNext(o);
                            }
                            emitter.onComplete();
                        }
                    }), BackpressureStrategy.ERROR);
                } else {
                    responseEmitter = Flowable.fromFuture((Future<?>) result);
                }
                return responseEmitter.map(o -> {
                    if (o instanceof MutableHttpResponse) {
                        return (MutableHttpResponse<?>) o;
                    } else {
                        res.body(o);
                        return res;
                    }
                }).switchIfEmpty(Flowable.fromCallable(() ->
                        res.status(HttpStatus.NOT_FOUND))
                );
            } else {
                final boolean isReactiveReturnType = Publishers.isConvertibleToPublisher(result);
                if (isReactiveReturnType) {
                    final Flowable<?> publisher;
                    boolean isSingle = Publishers.isSingle(result.getClass());
                    if (!isSingle) {
                        final Flowable<?> flowable = Publishers.convertPublisher(result, Flowable.class);
                        publisher = flowable.toList().toFlowable();
                    } else {
                        publisher = Publishers.convertPublisher(result, Flowable.class);
                    }
                    return publisher.map(o -> {
                        if (o instanceof MutableHttpResponse) {
                            return (MutableHttpResponse<?>) o;
                        } else {
                            res.body(o);
                            return res;
                        }
                    }).switchIfEmpty(Flowable.fromCallable(() -> res.status(HttpStatus.NOT_FOUND)));
                } else if (result instanceof MutableHttpResponse) {
                    return Publishers.just((MutableHttpResponse<?>) result);
                } else {
                    return Publishers.just(
                            res.body(result)
                    );
                }
            }

        });
        final List<HttpFilter> filters = router.findFilters(req);
        if (CollectionUtils.isNotEmpty(filters)) {
            responsePublisher =
                    filterPublisher(new AtomicReference<>(req), responsePublisher, isErrorRoute);
        }
        return responsePublisher;
    }

    private void encodeResponse(ServletExchange<Req, Res> exchange, MutableHttpResponse<?> response) {
        final Object body = response.getBody().orElse(null);
        if (body instanceof CharSequence) {
            if (!response.getContentType().isPresent()) {
                response.contentType(MediaType.TEXT_PLAIN_TYPE);
            }
            try {
                final BufferedWriter writer = exchange.getResponse().getWriter();
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
        } else if (body != null) {
            final MediaType ct = response.getContentType().orElseGet(() -> {
                final Produces ann = body.getClass().getAnnotation(Produces.class);
                if (ann != null) {
                    final String[] v = ann.value();
                    if (ArrayUtils.isNotEmpty(v)) {
                        final MediaType mediaType = new MediaType(v[0]);
                        response.contentType(mediaType);
                        return mediaType;
                    }
                }
                response.contentType(MediaType.APPLICATION_JSON_TYPE);
                return MediaType.APPLICATION_JSON_TYPE;
            });
            final MediaTypeCodec codec = mediaTypeCodecRegistry.findCodec(ct, body.getClass()).orElse(null);
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

    private void setHeadersFromMetadata(MutableHttpResponse<Object> res, AnnotationMetadata annotationMetadata, Object result) {
        final String contentType = annotationMetadata.stringValue(Produces.class)
                .orElse(getDefaultMediaType(result));
        if (contentType != null) {
            res.contentType(contentType);
        }

        annotationMetadata.enumValue(Status.class, HttpStatus.class)
                .ifPresent(res::status);
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
        if (result instanceof CharSequence) {
            return MediaType.TEXT_PLAIN;
        } else if (result != null) {
            return MediaType.APPLICATION_JSON;
        }
        return null;
    }

    private void handleException(
            HttpRequest<Object> req,
            MutableHttpResponse<Object> res,
            RouteMatch<?> route,
            boolean isErrorRoute,
            Throwable e,
            ServletExchange<Req, Res> exchange) {
        req.setAttribute(HttpAttributes.ERROR, e);
        if (isErrorRoute) {
            // handle error default
            if (LOG.isErrorEnabled()) {
                LOG.error("Error occurred executing Error route [" + route + "]: " + e.getMessage(), e);
            }
            res.status(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        } else {
            if (e instanceof UnsatisfiedRouteException || e instanceof ConversionErrorException) {
                final RouteMatch<Object> badRequestRoute = lookupStatusRoute(route, HttpStatus.BAD_REQUEST);
                if (badRequestRoute != null) {
                    invokeRouteMatch(req, res, badRequestRoute, true, exchange);
                } else {
                    invokeExceptionHandlerIfPossible(req, res, e, HttpStatus.BAD_REQUEST, exchange);
                }
            } else if (e instanceof HttpStatusException) {
                HttpStatusException statusException = (HttpStatusException) e;
                final HttpStatus status = statusException.getStatus();
                final int code = status.getCode();
                final boolean isErrorStatus = code >= 400;
                final RouteMatch<Object> statusRoute = isErrorStatus ? lookupStatusRoute(route, status) : null;
                if (statusRoute != null) {
                    invokeRouteMatch(req, res, statusRoute, true, exchange);
                } else {
                    res.status(code, statusException.getMessage());
                    final Object body = statusException.getBody().orElse(null);
                    if (body != null) {
                        res.body(body);
                    } else if (isErrorStatus) {
                        res.body(new JsonError(statusException.getMessage()));
                    }
                    encodeResponse(exchange, res);
                }

            } else {

                final RouteMatch<Object> errorRoute = lookupErrorRoute(route, e);
                if (errorRoute != null) {
                    invokeRouteMatch(req, res, errorRoute, true, exchange);
                } else {
                    invokeExceptionHandlerIfPossible(req, res, e, HttpStatus.INTERNAL_SERVER_ERROR, exchange);
                }
            }
        }
    }

    private void invokeExceptionHandlerIfPossible(HttpRequest<Object> req, MutableHttpResponse<Object> res, Throwable e, HttpStatus defaultStatus, ServletExchange<Req, Res> exchange) {
        final ExceptionHandler<Throwable, ?> exceptionHandler = lookupExceptionHandler(e);
        if (exceptionHandler != null) {
            try {
                ServerRequestContext.with(req, () -> {
                    final Object result = exceptionHandler.handle(req, e);
                    if (result instanceof MutableHttpResponse) {
                        encodeResponse(exchange, (MutableHttpResponse<?>) result);
                    } else if (result != null) {
                        final MutableHttpResponse<? super Object> response =
                                exchange.getResponse().status(defaultStatus).body(result);
                        encodeResponse(exchange, response);
                    }
                });
            } catch (Throwable ex) {
                if (LOG.isErrorEnabled()) {
                    LOG.error("Error occurred executing exception handler [" + exceptionHandler.getClass() + "]: " + e.getMessage(), e);
                }
                res.status(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            }
        } else {
            if (LOG.isErrorEnabled()) {
                LOG.error("Internal Server Error: " + e.getMessage(), e);
            }
            res.status(defaultStatus)
                .body(new JsonError(e.getMessage()));

            encodeResponse(exchange, res);
        }
    }

    @SuppressWarnings("unchecked")
    private ExceptionHandler<Throwable, ?> lookupExceptionHandler(Throwable e) {
        final Class<? extends Throwable> type = e.getClass();
        return applicationContext.findBean(ExceptionHandler.class, Qualifiers.byTypeArgumentsClosest(type, HttpResponse.class))
                .orElse(null);
    }

    private RouteMatch<Object> lookupErrorRoute(RouteMatch<?> route, Throwable e) {
        return router.route(route.getDeclaringType(), e)
                .orElseGet(() -> router.route(e).orElse(null));
    }

    private RouteMatch<Object> lookupStatusRoute(RouteMatch<?> route, HttpStatus status) {
        return router.route(route.getDeclaringType(), status)
                .orElseGet(() ->
                        router.route(status).orElse(null)
                );
    }

    private Publisher<? extends MutableHttpResponse<?>> filterPublisher(
            AtomicReference<io.micronaut.http.HttpRequest<?>> requestReference,
            Publisher<? extends MutableHttpResponse<?>> routePublisher,
            boolean skipOncePerRequest) {
        Publisher<? extends io.micronaut.http.MutableHttpResponse<?>> finalPublisher;
        List<HttpFilter> filters = new ArrayList<>(router.findFilters(requestReference.get()));
        if (skipOncePerRequest) {
            filters.removeIf(filter -> filter instanceof OncePerRequestHttpServerFilter);
        }
        if (!filters.isEmpty()) {
            // make the action executor the last filter in the chain
            //noinspection unchecked
            filters.add((HttpServerFilter) (req, chain) -> (Publisher<MutableHttpResponse<?>>) routePublisher);

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
                    HttpFilter httpFilter = filters.get(pos);
                    return (Publisher<MutableHttpResponse<?>>) httpFilter.doFilter(requestReference.getAndSet(request), this);
                }
            };
            HttpFilter httpFilter = filters.get(0);
            Publisher<? extends io.micronaut.http.HttpResponse<?>> resultingPublisher = httpFilter.doFilter(requestReference.get(), filterChain);
            //noinspection unchecked
            finalPublisher = (Publisher<? extends MutableHttpResponse<?>>) resultingPublisher;
        } else {
            finalPublisher = routePublisher;
        }
        return finalPublisher;
    }
}
