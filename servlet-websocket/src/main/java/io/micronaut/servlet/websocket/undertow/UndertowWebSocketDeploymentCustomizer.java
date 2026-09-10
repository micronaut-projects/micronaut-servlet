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
package io.micronaut.servlet.websocket.undertow;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.servlet.websocket.ServerContainerCustomizer;
import io.micronaut.servlet.websocket.ServletWebSocketConfiguration;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.websockets.extensions.PerMessageDeflateHandshake;
import io.undertow.websockets.jsr.WebSocketDeploymentInfo;
import jakarta.inject.Singleton;

import java.time.Duration;

/**
 * Ensures that a Jakarta {@code ServerContainer} is available on the Undertow servlet context.
 *
 * <p>Undertow's {@code io.undertow.websockets.jsr.Bootstrap} servlet extension is loaded by
 * service loading during deployment, but only acts when the deployment carries a
 * {@link WebSocketDeploymentInfo} servlet context attribute. Adding that attribute to the
 * {@link DeploymentInfo} bean is therefore all the bootstrap that is needed.</p>
 *
 * <p>Handlers are dispatched to a worker thread rather than run on the XNIO I/O thread, so
 * that blocking application code cannot stall the event loop.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
@Singleton
@Requires(classes = {WebSocketDeploymentInfo.class, DeploymentInfo.class})
@Requires(property = ServletWebSocketConfiguration.ENABLED, notEquals = StringUtils.FALSE, defaultValue = StringUtils.TRUE)
public final class UndertowWebSocketDeploymentCustomizer implements BeanCreatedEventListener<DeploymentInfo> {

    private final ServletWebSocketConfiguration configuration;
    private final Duration idleTimeout;

    /**
     * Default constructor.
     *
     * @param configuration       The WebSocket configuration
     * @param serverConfiguration The server configuration, used for the default idle timeout
     */
    public UndertowWebSocketDeploymentCustomizer(ServletWebSocketConfiguration configuration,
                                                 HttpServerConfiguration serverConfiguration) {
        this.configuration = configuration;
        this.idleTimeout = configuration.getIdleTimeout() != null
            ? configuration.getIdleTimeout()
            : serverConfiguration.getIdleTimeout();
    }

    @Override
    public DeploymentInfo onCreated(BeanCreatedEvent<DeploymentInfo> event) {
        DeploymentInfo deploymentInfo = event.getBean();
        if (deploymentInfo.getServletContextAttributes().containsKey(WebSocketDeploymentInfo.ATTRIBUTE_NAME)) {
            return deploymentInfo;
        }
        WebSocketDeploymentInfo webSocketDeploymentInfo = new WebSocketDeploymentInfo()
            .setDispatchToWorkerThread(true)
            .addListener(container -> ServerContainerCustomizer.apply(container, configuration, idleTimeout));
        if (configuration.getCompression().isEnabled()) {
            webSocketDeploymentInfo.addExtension(new PerMessageDeflateHandshake());
        }
        deploymentInfo.addServletContextAttribute(WebSocketDeploymentInfo.ATTRIBUTE_NAME, webSocketDeploymentInfo);
        return deploymentInfo;
    }
}
