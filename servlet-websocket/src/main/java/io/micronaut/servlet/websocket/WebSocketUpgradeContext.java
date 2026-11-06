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
package io.micronaut.servlet.websocket;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.websocket.context.WebSocketBean;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.Executor;

/**
 * The per-upgrade state a {@link MicronautServerEndpoint} needs, passed to the endpoint
 * through the user properties of the {@code ServerEndpointConfig} created for the upgrade.
 *
 * @param webSocketBean      The {@code @ServerWebSocket} bean handling the connection
 * @param originatingRequest The HTTP request that produced the handshake
 * @param routeMatch         The matched route
 * @param support            The shared services
 * @param jakartaEndpoint    What a {@code @ServerEndpoint} declared, or {@code null} for a Micronaut endpoint
 * @param routeExecutor      The executor of a WebSocket route of the route builder, which runs its handlers,
 *                           or {@code null} for a bean, whose methods select their executor
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
public record WebSocketUpgradeContext(WebSocketBean<Object> webSocketBean,
                                      HttpRequest<?> originatingRequest,
                                      UriRouteMatch<?, ?> routeMatch,
                                      ServletWebSocketSupport support,
                                      @Nullable JakartaEndpoint jakartaEndpoint,
                                      @Nullable Executor routeExecutor) {

    /**
     * Constructor for a Micronaut {@code @ServerWebSocket}.
     *
     * @param webSocketBean      The WebSocket bean
     * @param originatingRequest The handshake request
     * @param routeMatch         The matched route
     * @param support            The shared services
     */
    public WebSocketUpgradeContext(WebSocketBean<Object> webSocketBean,
                                   HttpRequest<?> originatingRequest,
                                   UriRouteMatch<?, ?> routeMatch,
                                   ServletWebSocketSupport support) {
        this(webSocketBean, originatingRequest, routeMatch, support, null, null);
    }

    /**
     * Constructor for a Micronaut {@code @ServerWebSocket} or a Jakarta {@code @ServerEndpoint}.
     *
     * @param webSocketBean      The WebSocket bean
     * @param originatingRequest The handshake request
     * @param routeMatch         The matched route
     * @param support            The shared services
     * @param jakartaEndpoint    What a {@code @ServerEndpoint} declared, or {@code null} for a Micronaut endpoint
     */
    public WebSocketUpgradeContext(WebSocketBean<Object> webSocketBean,
                                   HttpRequest<?> originatingRequest,
                                   UriRouteMatch<?, ?> routeMatch,
                                   ServletWebSocketSupport support,
                                   @Nullable JakartaEndpoint jakartaEndpoint) {
        this(webSocketBean, originatingRequest, routeMatch, support, jakartaEndpoint, null);
    }
}
