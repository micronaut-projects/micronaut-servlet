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
package io.micronaut.servlet.http.server.jdk.websocket;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsExchange;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.uri.QueryStringDecoder;
import jakarta.websocket.server.HandshakeRequest;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The handshake request of a WebSocket upgrade on the JDK server, copied from its exchange so
 * that it stays valid for as long as the session.
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
final class JdkHandshakeRequest implements HandshakeRequest {

    private final Map<String, List<String>> headers;
    private final URI requestUri;
    private final @Nullable String queryString;
    private final Map<String, List<String>> parameters;
    private final Map<String, String> pathParameters;
    private final @Nullable Principal principal;
    private final boolean secure;

    /**
     * @param exchange       The exchange of the handshake
     * @param pathParameters The values of the variables of the path of the endpoint
     */
    JdkHandshakeRequest(HttpExchange exchange, Map<String, String> pathParameters) {
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> copy.put(name, List.copyOf(values)));
        this.headers = Collections.unmodifiableMap(copy);
        this.requestUri = exchange.getRequestURI();
        this.queryString = requestUri.getRawQuery();
        this.parameters = Collections.unmodifiableMap(new QueryStringDecoder(requestUri).parameters());
        this.pathParameters = Map.copyOf(pathParameters);
        this.principal = exchange.getPrincipal();
        this.secure = exchange instanceof HttpsExchange;
    }

    @Override
    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    @Override
    public @Nullable Principal getUserPrincipal() {
        return principal;
    }

    @Override
    public URI getRequestURI() {
        return requestUri;
    }

    @Override
    public boolean isUserInRole(String role) {
        // the JDK server has no roles
        return false;
    }

    @Override
    public @Nullable Object getHttpSession() {
        // nor HTTP sessions
        return null;
    }

    @Override
    public Map<String, List<String>> getParameterMap() {
        return parameters;
    }

    @Override
    public @Nullable String getQueryString() {
        return queryString;
    }

    /**
     * @return The values of the variables of the path of the endpoint
     */
    Map<String, String> getPathParameters() {
        return pathParameters;
    }

    /**
     * @return Whether the handshake came over TLS
     */
    boolean isSecure() {
        return secure;
    }

    /**
     * @param name A header name
     * @return Its first value, if any
     */
    @Nullable String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
