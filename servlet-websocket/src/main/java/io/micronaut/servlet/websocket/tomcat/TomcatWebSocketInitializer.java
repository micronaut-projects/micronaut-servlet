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
package io.micronaut.servlet.websocket.tomcat;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.servlet.websocket.ServerContainerCustomizer;
import io.micronaut.servlet.websocket.ServletWebSocketConfiguration;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.websocket.server.ServerContainer;
import org.apache.tomcat.websocket.server.WsSci;

import java.time.Duration;
import java.util.Set;

/**
 * Ensures that a Jakarta {@code ServerContainer} is available on the Tomcat servlet context.
 *
 * <p>{@code Tomcat.addContext} attaches only a {@code FixContextListener} and never a
 * {@code ContextConfig}, so Tomcat's own {@code WsSci} is never discovered through
 * {@code META-INF/services} in embedded mode. Wrapping it in a bean is enough, because the
 * Micronaut Tomcat factory already registers every {@link ServletContainerInitializer}
 * bean with the context.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
@Singleton
@Requires(classes = WsSci.class)
@Requires(property = ServletWebSocketConfiguration.ENABLED, notEquals = StringUtils.FALSE, defaultValue = StringUtils.TRUE)
public final class TomcatWebSocketInitializer implements ServletContainerInitializer {

    private static final String SERVER_CONTAINER_ATTRIBUTE = "jakarta.websocket.server.ServerContainer";

    private final ServletWebSocketConfiguration configuration;
    private final Duration idleTimeout;
    private final WsSci delegate = new WsSci();

    /**
     * Default constructor.
     *
     * @param configuration       The WebSocket configuration
     * @param serverConfiguration The server configuration, used for the default idle timeout
     */
    public TomcatWebSocketInitializer(ServletWebSocketConfiguration configuration,
                                      HttpServerConfiguration serverConfiguration) {
        this.configuration = configuration;
        this.idleTimeout = configuration.getIdleTimeout() != null
            ? configuration.getIdleTimeout()
            : serverConfiguration.getIdleTimeout();
    }

    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext ctx) throws ServletException {
        delegate.onStartup(classes != null ? classes : Set.of(), ctx);
        if (ctx.getAttribute(SERVER_CONTAINER_ATTRIBUTE) instanceof ServerContainer container) {
            ServerContainerCustomizer.apply(container, configuration, idleTimeout);
        }
    }
}
