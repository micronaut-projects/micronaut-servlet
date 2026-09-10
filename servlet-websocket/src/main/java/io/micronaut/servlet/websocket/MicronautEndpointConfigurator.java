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
import jakarta.websocket.Extension;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The configurator for a Micronaut WebSocket upgrade.
 *
 * <p>Origin checking is deliberately permissive here because CORS and origin policy are
 * enforced by Micronaut filters, which have already run against the handshake by the time
 * the container is asked to upgrade. This matches the Netty server, which likewise has no
 * origin check of its own on the upgrade.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
final class MicronautEndpointConfigurator extends ServerEndpointConfig.Configurator {

    /**
     * Headers that belong to the container or to the WebSocket handshake itself and must
     * never be copied over from the response the Micronaut filter chain produced.
     */
    private static final Set<String> RESERVED_HEADERS = Set.of(
        "connection",
        "upgrade",
        "content-length",
        "content-type",
        "transfer-encoding",
        "sec-websocket-accept",
        "sec-websocket-protocol",
        "sec-websocket-extensions",
        "sec-websocket-version"
    );

    private final WebSocketUpgradeContext context;
    private final boolean compressionEnabled;
    private final Map<String, List<String>> handshakeHeaders;

    MicronautEndpointConfigurator(WebSocketUpgradeContext context,
                                  boolean compressionEnabled,
                                  Map<String, List<String>> handshakeHeaders) {
        this.context = context;
        this.compressionEnabled = compressionEnabled;
        this.handshakeHeaders = handshakeHeaders;
    }

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        if (endpointClass.isAssignableFrom(MicronautServerEndpoint.class)) {
            return endpointClass.cast(new MicronautServerEndpoint(context));
        }
        return super.getEndpointInstance(endpointClass);
    }

    @Override
    public boolean checkOrigin(String originHeaderValue) {
        return true;
    }

    @Override
    public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested) {
        if (!compressionEnabled) {
            return List.of();
        }
        return super.getNegotiatedExtensions(installed, requested);
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
        // Undertow rebuilds the ServerEndpointConfig during the upgrade and does not carry
        // the user properties across, so the context is put on whichever config the
        // endpoint will actually be opened with.
        sec.getUserProperties().put(MicronautServerEndpoint.CONTEXT_PROPERTY, context);
        if (handshakeHeaders.isEmpty()) {
            return;
        }
        Map<String, List<String>> target = response.getHeaders();
        handshakeHeaders.forEach((name, values) -> {
            if (!RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                target.put(name, List.copyOf(values));
            }
        });
    }
}
