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
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteMatch;
import org.jspecify.annotations.Nullable;

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
        this.routeMatch = router.<Object, Object>find(HttpMethod.GET, request.getPath(), request)
            .filter(match -> match.getRouteInfo().isWebSocketRoute())
            .findFirst()
            .orElse(null);

        MutableHttpResponse<?> proceed = HttpResponse.ok();
        if (routeMatch != null) {
            RouteAttributes.setRouteMatch(request, routeMatch);
            RouteAttributes.setRouteInfo(request, routeMatch.getRouteInfo());
            RouteAttributes.setRouteMatch(proceed, routeMatch);
            RouteAttributes.setRouteInfo(proceed, routeMatch.getRouteInfo());
        }

        ExecutionFlow<HttpResponse<?>> response;
        if (routeMatch != null) {
            response = runWithFilters(request, (filteredRequest, propagatedContext) -> ExecutionFlow.just(proceed));
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
