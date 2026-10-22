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
package io.micronaut.servlet.http.websocket;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.RequestLifecycle;
import io.micronaut.http.server.RouteExecutor;
import io.micronaut.web.router.DefaultRouter;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.websocket.WebSocketRouteEndpoint;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Runs the Micronaut server filter chain against a WebSocket upgrade request so that
 * filters and security see the handshake before the connection is switched over to the
 * WebSocket protocol.
 *
 * <p>This mirrors the behaviour of the Netty server: the filter chain is invoked with a
 * sentinel response, and the upgrade only proceeds when that exact response instance
 * survives the chain. A filter that produces its own response (a {@code 401} from
 * micronaut-security, for example) therefore cancels the upgrade and its response is
 * written as an ordinary HTTP response.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
public final class WebSocketUpgradeRequestLifecycle extends RequestLifecycle {

    private final Router router;
    private @Nullable UriRouteMatch<Object, Object> routeMatch;
    private boolean shouldProceedNormally;

    /**
     * Default constructor.
     *
     * @param routeExecutor The route executor
     * @param router        The router used to look up the WebSocket route
     */
    public WebSocketUpgradeRequestLifecycle(RouteExecutor routeExecutor, Router router) {
        super(routeExecutor);
        this.router = router;
    }

    /**
     * Match the request to a WebSocket route and run the filter chain against it.
     *
     * @param request The upgrade request
     * @return A flow producing either the sentinel response, meaning the upgrade may proceed,
     * or the response a filter substituted for it
     */
    public ExecutionFlow<HttpResponse<?>> handle(HttpRequest<?> request) {
        // the conditions of a route, e.g. a predicate that reads the current request, see the
        // upgrade request: the caller runs this with it as the current request
        this.routeMatch = findWebSocketRoute(router, request).orElse(null);

        MutableHttpResponse<?> proceed = HttpResponse.ok();
        if (routeMatch != null) {
            RouteAttributes.setRouteMatch(request, routeMatch);
            RouteAttributes.setRouteInfo(request, routeMatch.getRouteInfo());
            RouteAttributes.setRouteMatch(proceed, routeMatch);
            RouteAttributes.setRouteInfo(proceed, routeMatch.getRouteInfo());
        }

        ExecutionFlow<HttpResponse<?>> response;
        if (routeMatch != null) {
            // the filters of the route and of its groups apply too, e.g. those of a WebSocket route
            // of the route builder
            response = runWithFilters(request, routeMatch, (filteredRequest, propagatedContext) -> ExecutionFlow.just(proceed));
        } else {
            response = onError(request, new HttpStatusException(HttpStatus.NOT_FOUND, "WebSocket Not Found"))
                .putInContext(ServerRequestContext.KEY, request);
        }
        return response.map(actual -> {
            if (actual == proceed) {
                shouldProceedNormally = true;
            }
            return actual;
        });
    }

    /**
     * Find the WebSocket route of an upgrade request, like the Netty server. A WebSocket route of
     * the route builder is preferred: of the ones that accept the port and the conditions of the
     * request, the closest route, then the first of the lowest order, like the route of any other
     * request. Otherwise, the first route of a {@code @ServerWebSocket} bean whose template matches.
     *
     * @param router  The router
     * @param request The upgrade request
     * @return The WebSocket route, if any
     */
    private static Optional<UriRouteMatch<Object, Object>> findWebSocketRoute(Router router, HttpRequest<?> request) {
        // the conditions of the routes of the path are only evaluated when a WebSocket route of
        // the route builder has the path: a @ServerWebSocket upgrade only evaluates its own
        boolean anyFunctionalRoute = router.<Object, Object>findAny(request.getPath(), null)
            .anyMatch(match -> match.getHttpMethod() == HttpMethod.GET && isFunctionalWebSocketRoute(match));
        List<UriRouteMatch<Object, Object>> functionalRoutes = anyFunctionalRoute
            ? router.<Object, Object>findAny(request.getPath(), request)
                .filter(match -> match.getHttpMethod() == HttpMethod.GET && isFunctionalWebSocketRoute(match))
                .toList()
            : List.of();
        if (!functionalRoutes.isEmpty()) {
            return DefaultRouter.resolveAmbiguity(request, functionalRoutes).stream()
                .min(Comparator.comparingInt(match -> match.getRouteInfo().getOrder()));
        }
        return router.<Object, Object>find(HttpMethod.GET, request.getPath(), request)
            .filter(match -> match.getRouteInfo().isWebSocketRoute())
            .filter(match -> !isFunctionalWebSocketRoute(match))
            .filter(match -> match.getRouteInfo().matching(request))
            .findFirst();
    }

    /**
     * Whether a route is a WebSocket route of the route builder, which carries its endpoint,
     * rather than the route of a {@code @ServerWebSocket} bean. Only the name of the attribute is
     * read, a constant, so that this works without micronaut-websocket on the classpath.
     *
     * @param routeMatch A route match
     * @return Whether it is a WebSocket route of the route builder
     */
    private static boolean isFunctionalWebSocketRoute(RouteMatch<?> routeMatch) {
        return routeMatch.getRouteInfo().getAttributes().containsKey(WebSocketRouteEndpoint.ROUTE_ATTRIBUTE);
    }

    /**
     * @return Whether the upgrade should proceed, meaning no filter substituted a response
     */
    public boolean shouldProceedNormally() {
        return shouldProceedNormally;
    }

    /**
     * @return The matched WebSocket route, or {@code null} if no route matched
     */
    public @Nullable UriRouteMatch<Object, Object> getRouteMatch() {
        return routeMatch;
    }
}
