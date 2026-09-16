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
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpRequest;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.HandshakeResponse;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The configurator of a client connection: records the handshake request headers on the request
 * the handlers are bound against, and lets the configurator the endpoint declares see the
 * handshake as the specification promises.
 *
 * <p>A client has no upgrade request of its own, so the headers the container is about to send -
 * after the declared configurator had its say - are what {@code @Header} resolves against in the
 * handlers.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
final class MicronautClientConfigurator extends ClientEndpointConfig.Configurator {

    private final MutableHttpRequest<?> request;
    private final ClientEndpointConfig.@Nullable Configurator delegate;

    /**
     * @param request  The request the handshake headers are recorded on
     * @param delegate The configurator the endpoint declares, or {@code null} for none
     */
    MicronautClientConfigurator(MutableHttpRequest<?> request, ClientEndpointConfig.@Nullable Configurator delegate) {
        this.request = request;
        this.delegate = delegate;
    }

    @Override
    public void beforeRequest(Map<String, List<String>> headers) {
        if (delegate != null) {
            delegate.beforeRequest(headers);
        }
        MutableHttpHeaders target = request.getHeaders();
        headers.forEach((name, values) -> values.forEach(value -> target.add(name, value)));
    }

    @Override
    public void afterResponse(HandshakeResponse response) {
        if (delegate != null) {
            delegate.afterResponse(response);
        }
    }
}
