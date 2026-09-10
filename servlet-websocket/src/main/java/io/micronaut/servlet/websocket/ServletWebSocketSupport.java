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

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.RouteExecutor;
import io.micronaut.scheduling.executor.ExecutorSelector;
import io.micronaut.scheduling.executor.ThreadSelectionConfiguration;
import io.micronaut.websocket.bind.WebSocketStateBinderRegistry;
import jakarta.inject.Singleton;

import java.time.Duration;

/**
 * The services a {@link MicronautServerEndpoint} needs, resolved once and shared by every
 * WebSocket connection.
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
@Singleton
public final class ServletWebSocketSupport {

    private final ApplicationContext applicationContext;
    private final WebSocketStateBinderRegistry binderRegistry;
    private final ConversionService conversionService;
    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;
    private final ServletWebSocketMessageEncoder encoder;
    private final ServletWebSocketSessionRegistry sessionRegistry;
    private final ServletWebSocketConfiguration configuration;
    private final ExecutorSelector executorSelector;
    private final ThreadSelectionConfiguration threadSelectionConfiguration;
    private final Duration idleTimeout;

    /**
     * Default constructor.
     *
     * @param applicationContext         The application context, used to publish WebSocket events
     * @param requestBinderRegistry      The HTTP request binder registry
     * @param conversionService          The conversion service
     * @param messageBodyHandlerRegistry The message body handler registry
     * @param encoder                    The outbound message encoder
     * @param sessionRegistry            The session registry
     * @param configuration              The WebSocket configuration
     * @param routeExecutor              The route executor, used for {@code @ExecuteOn} support
     * @param serverConfiguration        The server configuration
     */
    public ServletWebSocketSupport(ApplicationContext applicationContext,
                                   RequestBinderRegistry requestBinderRegistry,
                                   ConversionService conversionService,
                                   MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                                   ServletWebSocketMessageEncoder encoder,
                                   ServletWebSocketSessionRegistry sessionRegistry,
                                   ServletWebSocketConfiguration configuration,
                                   RouteExecutor routeExecutor,
                                   HttpServerConfiguration serverConfiguration) {
        this.applicationContext = applicationContext;
        this.binderRegistry = new WebSocketStateBinderRegistry(requestBinderRegistry, conversionService);
        this.conversionService = conversionService;
        this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
        this.encoder = encoder;
        this.sessionRegistry = sessionRegistry;
        this.configuration = configuration;
        this.executorSelector = routeExecutor.getExecutorSelector();
        this.threadSelectionConfiguration = serverConfiguration;
        Duration configured = configuration.getIdleTimeout();
        this.idleTimeout = configured != null ? configured : serverConfiguration.getIdleTimeout();
    }

    /**
     * @return The application context
     */
    public ApplicationContext applicationContext() {
        return applicationContext;
    }

    /**
     * @return The registry used to bind WebSocket handler method arguments
     */
    public WebSocketStateBinderRegistry binderRegistry() {
        return binderRegistry;
    }

    /**
     * @return The conversion service
     */
    public ConversionService conversionService() {
        return conversionService;
    }

    /**
     * @return The message body handler registry
     */
    public MessageBodyHandlerRegistry messageBodyHandlerRegistry() {
        return messageBodyHandlerRegistry;
    }

    /**
     * @return The outbound message encoder
     */
    public ServletWebSocketMessageEncoder encoder() {
        return encoder;
    }

    /**
     * @return The session registry
     */
    public ServletWebSocketSessionRegistry sessionRegistry() {
        return sessionRegistry;
    }

    /**
     * @return The WebSocket configuration
     */
    public ServletWebSocketConfiguration configuration() {
        return configuration;
    }

    /**
     * @return The executor selector, which honours {@code @ExecuteOn}
     */
    public ExecutorSelector executorSelector() {
        return executorSelector;
    }

    /**
     * @return The thread selection configuration
     */
    public ThreadSelectionConfiguration threadSelectionConfiguration() {
        return threadSelectionConfiguration;
    }

    /**
     * @return How long a session may stay idle before it is closed
     */
    public Duration idleTimeout() {
        return idleTimeout;
    }
}
