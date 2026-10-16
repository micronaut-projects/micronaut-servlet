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

import io.micronaut.context.BeanContext;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.core.annotation.Internal;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.servlet.http.websocket.ServletWebSocketUpgrader;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.Router;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Warns at startup when the application declares WebSocket endpoints, {@code @ServerWebSocket}
 * beans or WebSocket routes of the route builder, that this server will not upgrade to, rather
 * than leave it to the first handshake to find out.
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
@Singleton
final class JdkWebSocketStartupCheck implements ApplicationEventListener<ServerStartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(JdkWebSocketStartupCheck.class);

    private final JdkWebSocketCapability capability;
    private final BeanContext beanContext;
    private final @Nullable Router router;

    JdkWebSocketStartupCheck(JdkWebSocketCapability capability, BeanContext beanContext, @Nullable Router router) {
        this.capability = capability;
        this.beanContext = beanContext;
        this.router = router;
    }

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        String problem = problem();
        if (problem != null) {
            LOG.warn(problem);
        }
    }

    /**
     * @return What stops the WebSocket endpoints of the application from working, if anything
     */
    @Nullable String problem() {
        if (router == null || router.uriRoutes().noneMatch(RouteInfo::isWebSocketRoute)) {
            return null;
        }
        if (!capability.isSupported()) {
            return "The application declares WebSocket endpoints, but their handshakes will be refused. "
                + capability.unsupportedMessage();
        }
        if (!beanContext.containsBean(ServletWebSocketUpgrader.class)) {
            return "The application declares WebSocket endpoints, but WebSocket support is missing from the classpath: "
                + "add io.micronaut.servlet:micronaut-servlet-websocket and jakarta.websocket:jakarta.websocket-api.";
        }
        return null;
    }
}
