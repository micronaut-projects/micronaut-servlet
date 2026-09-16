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
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.websocket.exceptions.WebSocketException;
import jakarta.inject.Singleton;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Encoder;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Extension;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * The Jakarta {@link WebSocketContainer} of a Micronaut servlet application, for opening client
 * connections.
 *
 * <p>Inject it and call {@link #connectToServer(Class, URI)} with a {@code @ClientEndpoint}
 * class compiled with the Micronaut servlet annotation processor. The connection is opened by
 * the servlet container's own client implementation, but the endpoint is a bean and its
 * handlers are invoked through the compiled bean metadata, so nothing is scanned or reflected
 * on. The handlers may take the container's {@link Session}, the {@code EndpointConfig}, the
 * Jakarta {@code CloseReason} and everything a Micronaut handler can take; a value an
 * {@code @OnMessage} method returns is sent to the peer; {@code decoders}, {@code encoders} and
 * {@code configurator} resolve as they do for a {@code @ServerEndpoint}.</p>
 *
 * <p>A class that is not such a bean - one compiled without the processor - is handed to the
 * container as it is, which then instantiates and scans it reflectively, as it would on its own.
 * The programmatic {@link Endpoint} forms and the container settings are the container's.</p>
 *
 * <p>Unlike {@code ContainerProvider.getWebSocketContainer()}, whose result depends on which
 * implementation {@code ServiceLoader} finds first, this bean is deterministic and is the
 * documented way to open a Jakarta client connection.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Singleton
@Requires(classes = WebSocketContainer.class)
@Requires(property = ServletWebSocketConfiguration.ENABLED_PROPERTY, notEquals = StringUtils.FALSE, defaultValue = StringUtils.TRUE)
public final class MicronautWebSocketContainer implements WebSocketContainer {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautWebSocketContainer.class);

    private final BeanContext beanContext;
    private final ServletWebSocketSupport support;
    private final WebSocketContainerHolder holder;
    private final Map<Class<?>, JakartaEndpoint> jakartaEndpoints = new ConcurrentHashMap<>();

    /**
     * Default constructor.
     *
     * @param beanContext The bean context
     * @param support     The shared infrastructure
     * @param holder      The servlet container's WebSocket container
     */
    public MicronautWebSocketContainer(BeanContext beanContext,
                                       ServletWebSocketSupport support,
                                       WebSocketContainerHolder holder) {
        this.beanContext = beanContext;
        this.support = support;
        this.holder = holder;
    }

    @Override
    public Session connectToServer(Class<?> annotatedEndpointClass, URI path) throws DeploymentException, IOException {
        BeanDefinition<Object> definition = clientEndpointDefinition(annotatedEndpointClass);
        if (definition == null) {
            return container().connectToServer(annotatedEndpointClass, path);
        }
        return connect(definition, beanContext.getBean(definition), path);
    }

    @Override
    public Session connectToServer(Object annotatedEndpointInstance, URI path) throws DeploymentException, IOException {
        BeanDefinition<Object> definition = clientEndpointDefinition(annotatedEndpointInstance.getClass());
        if (definition == null) {
            return container().connectToServer(annotatedEndpointInstance, path);
        }
        return connect(definition, annotatedEndpointInstance, path);
    }

    @Override
    public Session connectToServer(Endpoint endpointInstance, ClientEndpointConfig cec, URI path) throws DeploymentException, IOException {
        return container().connectToServer(endpointInstance, cec, path);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The endpoint is instantiated as a bean, through its introspection or reflectively under
     * the reflection policy, the way the components an endpoint declares are.</p>
     */
    @Override
    public Session connectToServer(Class<? extends Endpoint> endpointClass, ClientEndpointConfig cec, URI path) throws DeploymentException, IOException {
        Endpoint endpoint;
        try {
            endpoint = support.components().instantiate(endpointClass);
        } catch (WebSocketException e) {
            throw deploymentException(e);
        }
        return container().connectToServer(endpoint, cec, path);
    }

    /**
     * The bean definition of a {@code @ClientEndpoint} the processor compiled, whose handlers
     * are therefore executable.
     *
     * @return The definition, or {@code null} when the class is not such a bean
     */
    private @Nullable BeanDefinition<Object> clientEndpointDefinition(Class<?> type) {
        @SuppressWarnings("unchecked")
        BeanDefinition<Object> definition = beanContext.findBeanDefinition((Class<Object>) type).orElse(null);
        if (definition == null
            || !definition.hasAnnotation(JakartaEndpoint.CLIENT_ENDPOINT)
            || !JakartaEndpoint.isMapped(definition)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[{}] is not a @ClientEndpoint compiled with the Micronaut servlet processor, connecting through the container directly", type.getName());
            }
            return null;
        }
        return definition;
    }

    private Session connect(BeanDefinition<Object> definition, Object target, URI path) throws DeploymentException, IOException {
        Class<?> type = definition.getBeanType();
        JakartaEndpoint jakartaEndpoint = jakartaEndpoints.computeIfAbsent(type, ignored -> JakartaEndpoint.of(definition));
        MutableHttpRequest<Object> request = HttpRequest.GET(path);
        ClientEndpointConfig config = clientEndpointConfig(jakartaEndpoint, request);
        MicronautClientEndpoint endpoint = new MicronautClientEndpoint(
            support,
            ClientEndpointBean.of(definition, target),
            jakartaEndpoint,
            request,
            PropagatedContext.getOrEmpty()
        );
        if (LOG.isDebugEnabled()) {
            LOG.debug("Connecting WebSocket client endpoint [{}] to [{}]", type.getName(), path);
        }
        Session session = container().connectToServer(endpoint, config, path);
        awaitOpen(endpoint, session);
        return session;
    }

    private ClientEndpointConfig clientEndpointConfig(JakartaEndpoint jakartaEndpoint, MutableHttpRequest<?> request) throws DeploymentException {
        ClientEndpointConfig.Builder builder = ClientEndpointConfig.Builder.create()
            .configurator(new MicronautClientConfigurator(request, configurator(jakartaEndpoint)));
        if (!jakartaEndpoint.subprotocols().isEmpty()) {
            builder = builder.preferredSubprotocols(jakartaEndpoint.subprotocols());
        }
        List<Class<? extends Encoder>> containerEncoders = jakartaEndpoint.containerEncoders(support.components());
        if (!containerEncoders.isEmpty()) {
            builder = builder.encoders(containerEncoders);
        }
        return builder.build();
    }

    /**
     * The configurator the endpoint declares, one per connection as a Jakarta container has it:
     * a bean as its scope says, a plain class instantiated anew, so no handshake state is shared
     * between connections.
     */
    private ClientEndpointConfig.@Nullable Configurator configurator(JakartaEndpoint jakartaEndpoint) throws DeploymentException {
        Class<?> configurator = jakartaEndpoint.configurator();
        if (configurator == null) {
            return null;
        }
        try {
            return (ClientEndpointConfig.Configurator) support.components().instantiate(configurator);
        } catch (WebSocketException e) {
            throw deploymentException(e);
        }
    }

    /**
     * Waits for {@code @OnOpen} to complete, as the specification has the session returned only
     * once the endpoint is open, and reports its failure the way the container reports its own.
     */
    private static void awaitOpen(MicronautClientEndpoint endpoint, Session session) throws DeploymentException, IOException {
        try {
            endpoint.opened().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(session);
            throw new IOException("Interrupted while opening the WebSocket session", e);
        } catch (ExecutionException e) {
            closeQuietly(session);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw deploymentException(cause);
        }
    }

    private static void closeQuietly(Session session) {
        try {
            session.close();
        } catch (Exception e) {
            // the connection is going away anyway
        }
    }

    private static DeploymentException deploymentException(Throwable cause) {
        DeploymentException exception = new DeploymentException(cause.getMessage());
        exception.initCause(cause);
        return exception;
    }

    private WebSocketContainer container() throws DeploymentException {
        try {
            return holder.get();
        } catch (WebSocketException e) {
            throw deploymentException(e);
        }
    }

    @Override
    public long getDefaultAsyncSendTimeout() {
        return holder.get().getDefaultAsyncSendTimeout();
    }

    @Override
    public void setAsyncSendTimeout(long timeoutmillis) {
        holder.get().setAsyncSendTimeout(timeoutmillis);
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout() {
        return holder.get().getDefaultMaxSessionIdleTimeout();
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long timeout) {
        holder.get().setDefaultMaxSessionIdleTimeout(timeout);
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize() {
        return holder.get().getDefaultMaxBinaryMessageBufferSize();
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max) {
        holder.get().setDefaultMaxBinaryMessageBufferSize(max);
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize() {
        return holder.get().getDefaultMaxTextMessageBufferSize();
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max) {
        holder.get().setDefaultMaxTextMessageBufferSize(max);
    }

    @Override
    public Set<Extension> getInstalledExtensions() {
        return holder.get().getInstalledExtensions();
    }
}
