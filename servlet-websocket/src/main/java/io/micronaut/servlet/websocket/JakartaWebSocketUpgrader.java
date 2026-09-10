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

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.websocket.ServletWebSocketUpgrader;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.websocket.annotation.ServerWebSocket;
import io.micronaut.websocket.context.WebSocketBean;
import io.micronaut.websocket.context.WebSocketBeanRegistry;
import io.micronaut.websocket.exceptions.WebSocketException;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Switches a matched upgrade request over to the servlet container's Jakarta WebSocket
 * implementation.
 *
 * <p>No endpoint is ever registered with the container, so the container's own upgrade
 * filter never matches and can never bypass the Micronaut filter chain. Instead a
 * {@link ServerEndpointConfig} is built for the connection at hand and handed to
 * {@link ServerContainer#upgradeHttpToWebSocket}, which Jetty, Tomcat and Undertow all
 * implement as of Jakarta WebSocket 2.1.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
@Singleton
@Requires(classes = {ServerContainer.class, ServerWebSocket.class})
@Requires(property = ServletWebSocketConfiguration.ENABLED, notEquals = StringUtils.FALSE, defaultValue = StringUtils.TRUE)
public final class JakartaWebSocketUpgrader implements ServletWebSocketUpgrader {

    private static final Logger LOG = LoggerFactory.getLogger(JakartaWebSocketUpgrader.class);
    private static final String SERVER_CONTAINER_ATTRIBUTE = "jakarta.websocket.server.ServerContainer";

    private final ServletWebSocketSupport support;
    private final ServletWebSocketConfiguration configuration;
    private final WebSocketBeanRegistry webSocketBeanRegistry;

    /**
     * Default constructor.
     *
     * @param beanContext   The bean context
     * @param support       The shared WebSocket services
     * @param configuration The WebSocket configuration
     */
    public JakartaWebSocketUpgrader(BeanContext beanContext,
                                    ServletWebSocketSupport support,
                                    ServletWebSocketConfiguration configuration) {
        this.support = support;
        this.configuration = configuration;
        this.webSocketBeanRegistry = WebSocketBeanRegistry.forServer(beanContext);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void upgrade(ServletExchange<?, ?> exchange,
                        HttpRequest<?> request,
                        UriRouteMatch<?, ?> routeMatch,
                        HttpResponse<?> handshakeResponse) throws Exception {
        Object nativeRequest = exchange.getRequest().getNativeRequest();
        Object nativeResponse = exchange.getResponse().getNativeResponse();
        if (!(nativeRequest instanceof HttpServletRequest servletRequest)
            || !(nativeResponse instanceof HttpServletResponse servletResponse)) {
            throw new WebSocketException("WebSocket upgrade requires a servlet request and response");
        }

        ServerContainer container = resolveServerContainer(servletRequest);
        Class<?> declaringType = routeMatch.getRouteInfo().getDeclaringType();
        WebSocketBean<Object> webSocketBean = (WebSocketBean<Object>) webSocketBeanRegistry.getWebSocket(declaringType);

        // The servlet request is recycled the moment this dispatch returns, so it is copied
        // before the protocol switch: every handler runs after that point.
        WebSocketUpgradeContext context = new WebSocketUpgradeContext(
            webSocketBean,
            WebSocketHandshakeRequest.snapshot(request),
            routeMatch,
            support
        );

        ServerEndpointConfig.Builder builder = ServerEndpointConfig.Builder
            .create(MicronautServerEndpoint.class, request.getPath())
            .configurator(new MicronautEndpointConfigurator(
                context,
                configuration.getCompression().isEnabled(),
                handshakeHeaders(handshakeResponse)
            ));
        List<String> subprotocols = subprotocols(webSocketBean);
        if (!subprotocols.isEmpty()) {
            builder = builder.subprotocols(subprotocols);
        }
        ServerEndpointConfig endpointConfig = builder.build();
        endpointConfig.getUserProperties().put(MicronautServerEndpoint.CONTEXT_PROPERTY, context);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Upgrading request [{}] to WebSocket endpoint [{}]", request.getPath(), declaringType.getName());
        }
        container.upgradeHttpToWebSocket(servletRequest, servletResponse, endpointConfig, pathParameters(routeMatch));
    }

    private static ServerContainer resolveServerContainer(HttpServletRequest servletRequest) {
        Object attribute = servletRequest.getServletContext().getAttribute(SERVER_CONTAINER_ATTRIBUTE);
        if (attribute instanceof ServerContainer serverContainer) {
            return serverContainer;
        }
        throw new WebSocketException(
            "No jakarta.websocket.server.ServerContainer is available. Add the WebSocket implementation of the "
                + "servlet container to the classpath, or deploy to a container that supports Jakarta WebSocket 2.1."
        );
    }

    static List<String> subprotocols(WebSocketBean<?> webSocketBean) {
        String value = webSocketBean.getBeanDefinition()
            .stringValue(ServerWebSocket.class, "subprotocols")
            .orElse("");
        if (value.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String protocol : value.split(",")) {
            String trimmed = protocol.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static Map<String, String> pathParameters(UriRouteMatch<?, ?> routeMatch) {
        Map<String, Object> variableValues = routeMatch.getVariableValues();
        if (variableValues.isEmpty()) {
            return Map.of();
        }
        Map<String, String> pathParameters = new HashMap<>(variableValues.size());
        variableValues.forEach((name, value) -> {
            if (value != null) {
                pathParameters.put(name, value.toString());
            }
        });
        return pathParameters;
    }

    private static Map<String, List<String>> handshakeHeaders(HttpResponse<?> handshakeResponse) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        handshakeResponse.getHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, List.copyOf(values));
            }
        });
        return headers;
    }
}
