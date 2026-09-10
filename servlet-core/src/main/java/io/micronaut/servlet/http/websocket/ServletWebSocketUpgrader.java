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

import io.micronaut.core.annotation.Experimental;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.web.router.UriRouteMatch;
import org.jspecify.annotations.NonNull;

import java.util.Locale;

/**
 * Strategy for switching a matched WebSocket upgrade request over to a WebSocket
 * implementation once the Micronaut filter chain has approved the handshake.
 *
 * <p>An implementation of this interface is contributed by the
 * {@code micronaut-servlet-websocket} module. When no implementation is present the
 * server has no WebSocket support and upgrade requests are handled as ordinary HTTP
 * requests.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Experimental
public interface ServletWebSocketUpgrader {

    /**
     * The {@code websocket} token used by the {@code Upgrade} header.
     */
    String WEBSOCKET = "websocket";

    /**
     * Switch the given exchange over to the WebSocket protocol.
     *
     * <p>Implementations are invoked on the container thread that is dispatching the
     * request and before any asynchronous processing has been started, since servlet
     * containers require the upgrade to happen while the original request is still
     * being dispatched.</p>
     *
     * @param exchange          The exchange being upgraded
     * @param request           The originating HTTP request
     * @param routeMatch        The matched WebSocket route
     * @param handshakeResponse The response produced by the Micronaut filter chain, any
     *                          headers of which should be carried over to the handshake response
     * @throws Exception if the upgrade cannot be performed
     */
    void upgrade(@NonNull ServletExchange<?, ?> exchange,
                 @NonNull HttpRequest<?> request,
                 @NonNull UriRouteMatch<?, ?> routeMatch,
                 @NonNull HttpResponse<?> handshakeResponse) throws Exception;

    /**
     * Whether the given request is a WebSocket upgrade request.
     *
     * <p>A request qualifies when it is a {@code GET} request that carries
     * {@code Upgrade: websocket} and a {@code Connection} header listing the
     * {@code upgrade} token, as required by RFC 6455.</p>
     *
     * @param request The request
     * @return {@code true} if the request is a WebSocket upgrade request
     */
    static boolean isWebSocketUpgrade(@NonNull HttpRequest<?> request) {
        if (request.getMethod() != HttpMethod.GET) {
            return false;
        }
        HttpHeaders headers = request.getHeaders();
        String upgrade = headers.get(HttpHeaders.UPGRADE);
        if (upgrade == null || !upgrade.trim().equalsIgnoreCase(WEBSOCKET)) {
            return false;
        }
        for (String connection : headers.getAll(HttpHeaders.CONNECTION)) {
            for (String token : connection.split(",")) {
                if (token.trim().toLowerCase(Locale.ROOT).equals("upgrade")) {
                    return true;
                }
            }
        }
        return false;
    }
}
