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
import io.reactivex.*;
import io.reactivex.functions.Function;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
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
public abstract class ServletHttpHandler<Req, Res> implements AutoCloseable, LifeCycle<ServletHttpHandler<Req, Res>> {
    /**
     * Logger to be used by subclasses for logging.
     */
    protected static final Logger LOG = LoggerFactory.getLogger(ServletHttpHandler.class);

    private final Router router;
    private final RequestArgumentSatisfier requestArgumentSatisfier;
    private final MediaTypeCodecRegistry mediaTypeCodecRegistry;
    private final ApplicationContext applicationContext;
    private final Map<Class<?>, ServletResponseEncoder<?>> responseEncoders;

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

                if (LOG.isDebugEnabled()) {
                    LOG.debug("No matching routes found for {} - {}", req.getMethodName(), req.getPath());
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
                                    .anyMatch(rm -> rm.accept(contentType));
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
                            invokeRouteMatch(req, res, notAllowedRoute, true, exchange);
                        } else {
                            res.getHeaders().allowGeneric(existingRouteMethods);
                            res.status(HttpStatus.METHOD_NOT_ALLOWED)
                                    .body(new JsonError(
                                            "Method [" + req.getMethod() + "] not allowed for URI [" + req.getPath() + "]. Allowed methods: " + existingRouteMethods
                                    ));
                            encodeResponse(exchange, AnnotationMetadata.EMPTY_METADATA, res);
                        }
                    }
                } else {
                    handlePageNotFound(exchange, res, req);
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
        final RouteMatch<Object> notFoundRoute =
                router.route(httpStatus).orElse(null);

        if (notFoundRoute != null) {
            invokeRouteMatch(req, res, notFoundRoute, true, exchange);
        } else {
            res.status(httpStatus)
               .body(newJsonError(req, httpStatus.getReason()));
            encodeResponse(exchange, AnnotationMetadata.EMPTY_METADATA, res);
        }
    }

    @Override
    public void close() {
        if (applicationContext.isRunning()) {
            applicationContext.close();
        }
    }

    @Nonnull
    @Override
    public ServletHttpHandler<Req, Res> start() {
        if (!applicationContext.isRunning()) {
            applicationContext.start();
        }
        return this;
    }

    @Nonnull
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
            ServletExchange<Req, Res> exchange) {

        try {

            Publisher<? extends MutableHttpResponse<?>> responsePublisher = buildResponsePublisher(req, res, route, isErrorRoute);
            final ServletHttpRequest<Req, ? super Object> exchangeRequest = exchange.getRequest();
            boolean isAsyncSupported = exchangeRequest.isAsyncSupported();
            final Flowable<? extends MutableHttpResponse<?>> responseFlowable = Flowable.fromPublisher(responsePublisher)
                    .flatMap(response -> {
                        final HttpStatus status = response.status();
                        Object body = response.body();
                        if (body != null) {
                            if (Publishers.isConvertibleToPublisher(body)) {
                                boolean isSingle = Publishers.isSingle(body.getClass());
                                if (isSingle) {
                                    Flowable<?> flowable = Publishers.convertPublisher(body, Flowable.class);
                                    return flowable.map((Function<Object, MutableHttpResponse<?>>) o -> {
                                        if (o instanceof HttpResponse) {
                                            encodeResponse(exchange, route.getAnnotationMetadata(), (HttpResponse<?>) o);
                                            return res;
                                        } else {
                                            ServletHttpResponse<Res, ? super Object> res1 = exchange.getResponse();
                                            res1.body(o);
                                            encodeResponse(exchange, route.getAnnotationMetadata(), response);
                                            return res1;
                                        }
                                    }).switchIfEmpty(Flowable.defer(() -> {
                                        final RouteMatch<Object> errorRoute = lookupStatusRoute(route, HttpStatus.NOT_FOUND);
                                        if (errorRoute != null) {
                                            Flowable<MutableHttpResponse<?>> notFoundFlowable = Flowable.fromPublisher(buildResponsePublisher(
                                                    req,
                                                    (MutableHttpResponse<Object>) response,
                                                    errorRoute,
                                                    true
                                            ));
                                            return notFoundFlowable.onErrorReturn(throwable -> {

                                                if (LOG.isErrorEnabled()) {
                                                    LOG.error("Error occuring invoking 404 handler: " + throwable.getMessage());
                                                }
                                                MutableHttpResponse<Object> defaultNotFound = res.status(404).body(newJsonError(req, "Page Not Found"));
                                                encodeResponse(exchange, route.getAnnotationMetadata(), defaultNotFound);
                                                return defaultNotFound;
                                            });
                                        } else {
                                            return Publishers.just(res.status(404).body(newJsonError(req, "Page Not Found")));
                                        }
                                    }));
                                } else {
                                    // stream case
                                    Flowable<?> flowable = Publishers.convertPublisher(body, Flowable.class);
                                    if (isAsyncSupported) {
                                        final ServletHttpResponse<Res, ? super Object> servletResponse = exchange.getResponse();
                                        setHeadersFromMetadata(exchange.getResponse(), route.getAnnotationMetadata(), body);
                                        return servletResponse.stream(flowable);
                                    } else {
                                        // fallback to blocking
                                        return flowable.toList().map(list -> {
                                            final ServletHttpResponse<Res, ? super Object> servletHttpResponse = exchange.getResponse();
                                            encodeResponse(exchange, route.getAnnotationMetadata(), servletHttpResponse.body(list));
                                            return servletHttpResponse;
                                        }).toFlowable();
                                    }
                                }
                            } else {

                                if (!isErrorRoute && status.getCode() >= 400) {
                                    final RouteMatch<Object> errorRoute = lookupStatusRoute(route, status);
                                    if (errorRoute != null) {
                                        return buildErrorRouteHandler(exchange, req, (MutableHttpResponse<Object>) response, errorRoute);
                                    }
                                }
                            }
                        }

                        if (body != null) {
                            Class<?> bodyType = body.getClass();
                            ServletResponseEncoder<Object> responseEncoder = (ServletResponseEncoder<Object>) responseEncoders.get(bodyType);
                            if (responseEncoder != null) {
                                return responseEncoder.encode(exchange, route.getAnnotationMetadata(), body);
                            }
                        }

                        if (!isErrorRoute && status.getCode() >= 400) {
                            final RouteMatch<Object> errorRoute = lookupStatusRoute(route, status);
                            if (errorRoute != null) {
                                return buildErrorRouteHandler(exchange, req, (MutableHttpResponse<Object>) response, errorRoute);
                            }
                        }

                        return Flowable.fromCallable(() -> {
                            encodeResponse(exchange, route.getAnnotationMetadata(), response);
                            return response;
                        });
                    }).onErrorResumeNext(throwable -> {
                        handleException(req, res, route, isErrorRoute, throwable, exchange);
                        return Flowable.error(throwable);
                    });


            if (isAsyncSupported) {

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
                responseFlowable
                        .blockingSubscribe(response -> {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Request [{} - {}] completed successfully", req.getMethodName(), req.getUri());
                            }
                        }, throwable -> {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Request [" + req.getMethodName() + " - " + req.getUri() + "] completed with error: " + throwable.getMessage(), throwable);
                            }
                        });
            }
        } catch (Throwable e) {
            handleException(req, res, route, isErrorRoute, e, exchange);
        }
    }

    private Publisher<? extends MutableHttpResponse<?>> buildErrorRouteHandler(
            ServletExchange<Req, Res> exchange,
            HttpRequest<Object> request,
            MutableHttpResponse<Object> response,
            RouteMatch<Object> errorRoute) {
        return Publishers.map(buildResponsePublisher(
                request,
                response,
                errorRoute,
                true
        ), servletResponse -> {
            encodeResponse(exchange, errorRoute.getAnnotationMetadata(), servletResponse);
            return servletResponse;
        });
    }

    private Publisher<? extends MutableHttpResponse<?>> buildResponsePublisher(
            HttpRequest<Object> req,
            MutableHttpResponse<Object> res,
            RouteMatch<?> route,
            boolean isErrorRoute) {
        Publisher<? extends MutableHttpResponse<?>> responsePublisher
                = Flowable.<MutableHttpResponse<?>>defer(() -> {
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

            RouteMatch<?> finalComputedRoute = computedRoute;
            Object result = ServerRequestContext.with(req, (Callable<Object>) finalComputedRoute::execute);
            if (result instanceof Optional) {
                result = ((Optional<?>) result).orElse(null);
            }
            MutableHttpResponse<Object> httpResponse = res;
            if (result instanceof MutableHttpResponse) {
                httpResponse = (MutableHttpResponse<Object>) result;
                result = httpResponse.body();
            }
            final ReturnType<?> returnType = computedRoute.getReturnType();
            final Argument<?> genericReturnType = returnType.asArgument();
            final Class<?> javaReturnType = returnType.getType();
            if (result == null) {
                boolean isVoid = javaReturnType == void.class ||
                        Completable.class.isAssignableFrom(javaReturnType) ||
                        (genericReturnType.getFirstTypeVariable()
                                .map(arg -> arg.getType() == Void.class).orElse(false));
                if (isVoid) {
                    return Publishers.just(httpResponse);
                } else {
                    return Publishers.just(HttpResponse.notFound());
                }
            }

            Argument<?> firstArg = genericReturnType.getFirstTypeVariable().orElse(null);
            if (result instanceof Future) {
                if (result instanceof CompletionStage) {
                    CompletionStage<?> cs = (CompletionStage<?>) result;
                    result = Maybe.create(emitter -> cs.whenComplete((o, throwable) -> {
                        if (throwable != null) {
                            emitter.onError(throwable);
                        } else {
                            if (o != null) {
                                emitter.onSuccess(o);
                            } else {
                                emitter.onComplete();
                            }
                        }
                    }));
                } else {
                    result = Single.fromFuture((Future<?>) result);
                }
            }

            if (firstArg != null && HttpResponse.class.isAssignableFrom(firstArg.getType()) && Publishers.isConvertibleToPublisher(result)) {
                //noinspection unchecked
                return Publishers.convertPublisher(result, Flowable.class);
            } else {
                return Publishers.just(
                        httpResponse.body(result)
                );
            }
        });
        final List<HttpFilter> filters = router.findFilters(req);
        if (CollectionUtils.isNotEmpty(filters)) {
            responsePublisher =
                    filterPublisher(new AtomicReference<>(req), responsePublisher, isErrorRoute);
        }
        return responsePublisher;
    }

    private void encodeResponse(ServletExchange<Req, Res> exchange, AnnotationMetadata annotationMetadata, HttpResponse<?> response) {
        final Object body = response.getBody().orElse(null);
        setHeadersFromMetadata(exchange.getResponse(), annotationMetadata, body);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Sending response {}", response.status());
            traceHeaders(response.getHeaders());
        }
        if (body instanceof HttpStatus) {
            exchange.getResponse().status((HttpStatus) body);
        } else if (body instanceof CharSequence) {
            if (response instanceof MutableHttpResponse) {
                if (!response.getContentType().isPresent()) {
                    ((MutableHttpResponse<?>) response).contentType(MediaType.TEXT_PLAIN_TYPE);
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
        } else if (body != null) {
            Class<?> bodyType = body.getClass();
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

    private void setHeadersFromMetadata(MutableHttpResponse<Object> res, AnnotationMetadata annotationMetadata, Object result) {
        if (!res.getContentType().isPresent()) {
            final String contentType = annotationMetadata.stringValue(Produces.class)
                    .orElse(getDefaultMediaType(result));
            if (contentType != null) {
                res.contentType(contentType);
            } else if (result instanceof CharSequence) {
                res.contentType(MediaType.TEXT_PLAIN);
            }
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
                        res.body(newJsonError(req, statusException.getMessage()));
                    }
                    encodeResponse(exchange, AnnotationMetadata.EMPTY_METADATA, res);
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
                                invokeRouteMatch(req, res, badRequestRoute, true, exchange);
                            } else {
                                invokeExceptionHandlerIfPossible(req, res, e, HttpStatus.BAD_REQUEST, exchange);
                            }
                            return;
                        }
                    }
                }
                if (errorRoute != null) {
                    invokeRouteMatch(req, res, errorRoute, true, exchange);
                } else {
                    invokeExceptionHandlerIfPossible(req, res, e, HttpStatus.INTERNAL_SERVER_ERROR, exchange);
                }
            }
        }
    }

    private void invokeExceptionHandlerIfPossible(
            HttpRequest<Object> req,
            MutableHttpResponse<Object> res,
            Throwable e,
            HttpStatus defaultStatus,
            ServletExchange<Req, Res> exchange) {
        final ExceptionHandler<Throwable, ?> exceptionHandler = lookupExceptionHandler(e);
        if (exceptionHandler != null) {
            try {
                ServerRequestContext.with(req, () -> {
                    final Object result = exceptionHandler.handle(req, e);
                    if (result instanceof MutableHttpResponse) {
                        encodeResponse(exchange, AnnotationMetadata.EMPTY_METADATA, (MutableHttpResponse<?>) result);
                    } else if (result != null) {
                        final MutableHttpResponse<? super Object> response =
                                exchange.getResponse().status(defaultStatus).body(result);
                        encodeResponse(exchange, AnnotationMetadata.EMPTY_METADATA, response);
                    }
                });
            } catch (Throwable ex) {
                if (LOG.isErrorEnabled()) {
                    LOG.error("Error occurred executing exception handler [" + exceptionHandler.getClass() + "]: " + e.getMessage(), e);
                }
                res.status(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            }
        } else {
            if (defaultStatus.getCode() >= 500) {
                if (LOG.isErrorEnabled()) {
                    LOG.error(defaultStatus.getReason() + ": " + e.getMessage(), e);
                }
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(defaultStatus.getReason() + ": " + e.getMessage(), e);
                }
            }
            res.status(defaultStatus)
                    .body(newJsonError(req, e.getMessage()));

            encodeResponse(exchange, AnnotationMetadata.EMPTY_METADATA, res);
        }
    }

    private JsonError newJsonError(HttpRequest<Object> req, String message) {
        JsonError jsonError = new JsonError(message);
        jsonError.link("self", req.getPath());
        return jsonError;
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
