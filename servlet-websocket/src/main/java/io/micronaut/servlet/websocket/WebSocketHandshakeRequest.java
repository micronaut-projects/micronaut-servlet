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
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.simple.SimpleHttpRequest;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

/**
 * A detached snapshot of the HTTP request that produced a WebSocket handshake.
 *
 * <p>A servlet request is only valid for the duration of its dispatch. Once the container
 * has switched the connection over to the WebSocket protocol it recycles the request, so
 * anything reading from it afterwards fails. Every WebSocket handler outlives that
 * dispatch, and the Micronaut model gives handlers access to the originating request, so
 * the request is copied eagerly while the upgrade is still being dispatched.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
final class WebSocketHandshakeRequest extends SimpleHttpRequest<Object> {

    private final @Nullable InetSocketAddress remoteAddress;
    private final @Nullable InetSocketAddress serverAddress;
    private final boolean secure;

    private WebSocketHandshakeRequest(String uri,
                                      @Nullable InetSocketAddress remoteAddress,
                                      @Nullable InetSocketAddress serverAddress,
                                      boolean secure) {
        super(HttpMethod.GET, uri, null);
        this.remoteAddress = remoteAddress;
        this.serverAddress = serverAddress;
        this.secure = secure;
    }

    /**
     * Copies the given request.
     *
     * @param request The request to copy, which must still be valid
     * @return A detached copy that stays usable for the life of the WebSocket connection
     */
    static WebSocketHandshakeRequest snapshot(HttpRequest<?> request) {
        WebSocketHandshakeRequest copy = new WebSocketHandshakeRequest(
            request.getUri().toString(),
            addressOf(request, true),
            addressOf(request, false),
            isSecure(request)
        );
        request.getHeaders().forEach((name, values) -> values.forEach(value -> copy.getHeaders().add(name, value)));
        request.getParameters().forEach((name, values) -> copy.getParameters().add(name, List.copyOf(values)));
        Set<Cookie> cookies = request.getCookies().getAll();
        if (!cookies.isEmpty()) {
            copy.cookies(cookies);
        }
        MutableConvertibleValues<Object> attributes = copy.getAttributes();
        request.getAttributes().forEach((name, value) -> attributes.put(name, value));
        return copy;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return remoteAddress != null ? remoteAddress : super.getRemoteAddress();
    }

    @Override
    public InetSocketAddress getServerAddress() {
        return serverAddress != null ? serverAddress : super.getServerAddress();
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    private static @Nullable InetSocketAddress addressOf(HttpRequest<?> request, boolean remote) {
        try {
            return remote ? request.getRemoteAddress() : request.getServerAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isSecure(HttpRequest<?> request) {
        try {
            return request.isSecure();
        } catch (Exception e) {
            return false;
        }
    }
}
