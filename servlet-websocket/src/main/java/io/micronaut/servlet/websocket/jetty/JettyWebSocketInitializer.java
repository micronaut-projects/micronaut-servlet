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
package io.micronaut.servlet.websocket.jetty;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.servlet.websocket.ServerContainerCustomizer;
import io.micronaut.servlet.websocket.ServletWebSocketConfiguration;
import io.micronaut.servlet.websocket.WebSocketContainerHolder;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.websocket.server.ServerContainer;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;

import java.time.Duration;
import java.util.Set;

/**
 * Ensures that a Jakarta {@code ServerContainer} is available on the Jetty servlet context.
 *
 * <p>Jetty publishes the container through
 * {@code JakartaWebSocketServletContainerInitializer}, which in embedded mode is never
 * discovered by service loading because the Micronaut Jetty factory builds a plain
 * {@code ServletContextHandler}. Registering this initializer as a bean is enough: the
 * Jetty factory already adds every {@link ServletContainerInitializer} bean to the
 * context.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
@Singleton
@Requires(classes = {JakartaWebSocketServletContainerInitializer.class, ServletContextHandler.class})
// only for the Jetty runtime, so that another runtime enabled in its place on the same classpath does not receive it
@Requires(property = HttpServerConfiguration.PREFIX + ".jetty.enabled", notEquals = StringUtils.FALSE, defaultValue = StringUtils.TRUE)
@Requires(property = ServletWebSocketConfiguration.ENABLED_PROPERTY, notEquals = StringUtils.FALSE, defaultValue = StringUtils.TRUE)
public final class JettyWebSocketInitializer implements ServletContainerInitializer {

    private final ServletWebSocketConfiguration configuration;
    private final Duration idleTimeout;
    private final WebSocketContainerHolder holder;

    /**
     * Default constructor.
     *
     * @param configuration       The WebSocket configuration
     * @param serverConfiguration The server configuration, used for the default idle timeout
     * @param holder              Where the container is registered for client connections
     */
    public JettyWebSocketInitializer(ServletWebSocketConfiguration configuration,
                                     HttpServerConfiguration serverConfiguration,
                                     WebSocketContainerHolder holder) {
        this.configuration = configuration;
        this.holder = holder;
        this.idleTimeout = configuration.getIdleTimeout() != null
            ? configuration.getIdleTimeout()
            : serverConfiguration.getIdleTimeout();
    }

    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext ctx) throws ServletException {
        ServletContextHandler contextHandler = ServletContextHandler.getServletContextHandler(ctx, "Jakarta WebSocket");
        ServerContainer container = JakartaWebSocketServletContainerInitializer.initialize(contextHandler);
        ServerContainerCustomizer.apply(container, configuration, idleTimeout);
        holder.register(container);
    }
}
