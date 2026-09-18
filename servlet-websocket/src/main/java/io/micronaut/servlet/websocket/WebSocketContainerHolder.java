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
import io.micronaut.websocket.exceptions.WebSocketException;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletContext;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.jspecify.annotations.Nullable;

/**
 * The servlet container's Jakarta WebSocket container, for opening client connections.
 *
 * <p>Every servlet container's {@code ServerContainer} is also a client container, so the one
 * the server side upgrades through is what the client side connects through. The container
 * initializers register it as they create it. In a WAR deployment, where the container ran its
 * own initializer, it is read from the servlet context; with no server at all it is whatever
 * {@link ContainerProvider} finds, which is the container's own client implementation.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
@Singleton
public final class WebSocketContainerHolder {

    /**
     * The servlet context attribute under which a container publishes its {@code ServerContainer}.
     */
    public static final String SERVER_CONTAINER_ATTRIBUTE = "jakarta.websocket.server.ServerContainer";

    private final Object lock = new Object();
    private @Nullable WebSocketContainer container;
    private final @Nullable ServletContext servletContext;

    /**
     * Default constructor.
     *
     * @param servletContext The servlet context, a bean only when deployed as a web archive
     */
    public WebSocketContainerHolder(@Nullable ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    /**
     * Registers the container the server created.
     *
     * @param webSocketContainer The container
     */
    public void register(WebSocketContainer webSocketContainer) {
        synchronized (lock) {
            container = webSocketContainer;
        }
    }

    /**
     * The container client connections are opened through.
     *
     * @return The container
     * @throws WebSocketException if no Jakarta WebSocket implementation is available
     */
    public WebSocketContainer get() {
        synchronized (lock) {
            // Resolved once: a container found through the provider starts threads of its own.
            if (container == null) {
                container = resolve();
            }
            return container;
        }
    }

    private WebSocketContainer resolve() {
        if (servletContext != null && servletContext.getAttribute(SERVER_CONTAINER_ATTRIBUTE) instanceof WebSocketContainer published) {
            return published;
        }
        try {
            return ContainerProvider.getWebSocketContainer();
        } catch (RuntimeException e) {
            throw new WebSocketException("No Jakarta WebSocket container is available. Add the WebSocket "
                + "implementation of the servlet container to the classpath", e);
        }
    }
}
