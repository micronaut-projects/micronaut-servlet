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
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.cors.CorsOriginConfiguration;
import org.jspecify.annotations.Nullable;
import jakarta.websocket.Extension;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    private static final Logger LOG = LoggerFactory.getLogger(MicronautEndpointConfigurator.class);

    /**
     * Stands in for a regex that could not be compiled, so an invalid configuration rejects
     * rather than throwing on every handshake.
     */
    private static final Pattern NEVER_MATCHES = Pattern.compile("(?!)");

    /**
     * Shared, because a configurator is created for every upgrade.
     */
    private static final Map<String, Pattern> COMPILED_ORIGIN_PATTERNS = new ConcurrentHashMap<>();

    private final WebSocketUpgradeContext context;
    private final boolean compressionEnabled;
    private final Map<String, List<String>> handshakeHeaders;
    private final HttpServerConfiguration.CorsConfiguration corsConfiguration;
    private final @Nullable String sameOrigin;
    private final @Nullable CorsOriginConfiguration routeCorsConfiguration;

    MicronautEndpointConfigurator(WebSocketUpgradeContext context,
                                  boolean compressionEnabled,
                                  Map<String, List<String>> handshakeHeaders,
                                  HttpServerConfiguration.CorsConfiguration corsConfiguration,
                                  @Nullable CorsOriginConfiguration routeCorsConfiguration,
                                  @Nullable String sameOrigin) {
        this.context = context;
        this.compressionEnabled = compressionEnabled;
        this.handshakeHeaders = handshakeHeaders;
        this.corsConfiguration = corsConfiguration;
        this.routeCorsConfiguration = routeCorsConfiguration;
        this.sameOrigin = sameOrigin;
    }

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        if (endpointClass.isAssignableFrom(MicronautServerEndpoint.class)) {
            return endpointClass.cast(new MicronautServerEndpoint(context));
        }
        return super.getEndpointInstance(endpointClass);
    }

    /**
     * {@inheritDoc}
     *
     * <p>When CORS is configured this enforces the configured allow-list, rejecting the
     * handshake for any other origin. A browser does not apply its usual CORS response
     * check to a WebSocket handshake, so unlike an ordinary cross-origin request nothing
     * else would stop a hostile page from opening an authenticated socket.</p>
     *
     * <p>A same-origin handshake and a handshake with no origin at all are always accepted.
     * With CORS disabled every origin is accepted, matching the Netty server.</p>
     */
    @Override
    public boolean checkOrigin(String originHeaderValue) {
        if (routeCorsConfiguration == null && !corsConfiguration.isEnabled()) {
            return true;
        }
        if (StringUtils.isEmpty(originHeaderValue)) {
            // Not a browser initiated handshake, so there is no origin policy to apply.
            return true;
        }
        if (originHeaderValue.equals(sameOrigin)) {
            // A browser always sends Origin on a WebSocket handshake, including for a
            // same-origin socket, which a CORS allow-list is not meant to govern.
            return true;
        }
        if (routeCorsConfiguration != null) {
            // A @CrossOrigin on the endpoint states the policy for that endpoint, so it
            // decides on its own rather than being combined with the global one.
            return allowed(matchesOrigin(routeCorsConfiguration, originHeaderValue), originHeaderValue);
        }
        return allowed(
            corsConfiguration.getConfigurations().values().stream()
                .anyMatch(configuration -> matchesOrigin(configuration, originHeaderValue)),
            originHeaderValue
        );
    }

    private static boolean allowed(boolean allowed, String originHeaderValue) {
        if (!allowed && LOG.isDebugEnabled()) {
            LOG.debug("Rejecting WebSocket handshake from origin [{}]: no CORS configuration allows it", originHeaderValue);
        }
        return allowed;
    }

    private static boolean matchesOrigin(CorsOriginConfiguration configuration, String requestOrigin) {
        String regex = configuration.getAllowedOriginsRegex().orElse(null);
        if (regex != null && matchesRegex(regex, requestOrigin)) {
            return true;
        }
        List<String> allowedOrigins = configuration.getAllowedOrigins();
        if (allowedOrigins.isEmpty()) {
            return false;
        }
        if (regex == null && allowedOrigins.equals(CorsOriginConfiguration.ANY)) {
            return true;
        }
        return allowedOrigins.contains(requestOrigin);
    }

    private static boolean matchesRegex(String regex, String requestOrigin) {
        Pattern pattern = COMPILED_ORIGIN_PATTERNS.computeIfAbsent(regex, candidate -> {
            try {
                return Pattern.compile(candidate);
            } catch (PatternSyntaxException e) {
                LOG.warn("Invalid CORS allowed-origins-regex [{}]: {}", candidate, e.getMessage());
                return NEVER_MATCHES;
            }
        });
        return pattern.matcher(requestOrigin).matches();
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
        if (sec != null && context != null) {
            sec.getUserProperties().put(MicronautServerEndpoint.CONTEXT_PROPERTY, context);
        }
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
