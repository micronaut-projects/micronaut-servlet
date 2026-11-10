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
package io.micronaut.servlet.undertow;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.websockets.jsr.WebSocketDeploymentInfo;
import jakarta.inject.Singleton;

/**
 * Gives the JSR-356 container of a generation's deployment, which the WebSocket support of the servlet module
 * configures, the worker of the Undertow server kept across restarts and a buffer pool of it. Without them, Undertow
 * uses its static default container, whose worker threads start with the context class loader of the generation that
 * first needs it, and keep it, and that generation, from then on.
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@Requires(classes = WebSocketDeploymentInfo.class)
@DevelopmentActive
// after the WebSocket support of the servlet module, which adds the WebSocket deployment
@Order(Ordered.LOWEST_PRECEDENCE)
final class DevelopmentUndertowWebSockets implements BeanCreatedEventListener<DeploymentInfo> {

    private final RetainedUndertowServer retained;

    /**
     * @param retained The server kept across restarts
     */
    DevelopmentUndertowWebSockets(RetainedUndertowServer retained) {
        this.retained = retained;
    }

    @Override
    public DeploymentInfo onCreated(BeanCreatedEvent<DeploymentInfo> event) {
        DeploymentInfo deploymentInfo = event.getBean();
        if (deploymentInfo.getServletContextAttributes().get(WebSocketDeploymentInfo.ATTRIBUTE_NAME) instanceof WebSocketDeploymentInfo webSockets) {
            if (webSockets.getWorker() == null) {
                // looked up as a container needs it: the kept server starts after the first generation deploys
                webSockets.setWorker(retained::worker);
            }
            if (webSockets.getBuffers() == null) {
                webSockets.setBuffers(retained.webSocketBuffers());
            }
        }
        return deploymentInfo;
    }
}
