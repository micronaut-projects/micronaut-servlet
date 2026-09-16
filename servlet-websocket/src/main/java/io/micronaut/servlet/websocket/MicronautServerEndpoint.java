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
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.http.context.ServerHttpRequestContext;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.event.WebSocketSessionClosedEvent;
import io.micronaut.websocket.event.WebSocketSessionOpenEvent;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link jakarta.websocket.Endpoint} that adapts a connection the server accepted onto a
 * Micronaut {@code @ServerWebSocket} bean, or onto a Jakarta {@code @ServerEndpoint} the
 * annotation processor mapped onto one.
 *
 * <p>One instance exists per connection. All per-connection state arrives through the
 * {@link EndpointConfig} user properties, so the class also works on containers that
 * instantiate endpoints reflectively rather than through the configurator. The dispatch itself
 * is {@link AbstractMicronautEndpoint}'s; this class adds what only a server connection has: the
 * upgrade request the handlers are bound against, the route's path variables, the session
 * registry and the open and close events.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
public class MicronautServerEndpoint extends AbstractMicronautEndpoint {

    /**
     * The user property under which the {@link WebSocketUpgradeContext} is passed.
     */
    public static final String CONTEXT_PROPERTY = "io.micronaut.servlet.websocket.CONTEXT";

    private static final Logger LOG = LoggerFactory.getLogger(MicronautServerEndpoint.class);

    private @Nullable WebSocketUpgradeContext context;

    /**
     * Public no-argument constructor, required by containers that create endpoint
     * instances reflectively rather than through the configurator. The context is then
     * read from the {@link EndpointConfig} user properties instead.
     */
    public MicronautServerEndpoint() {
    }

    /**
     * Constructor used by {@link MicronautEndpointConfigurator}.
     *
     * @param context The per-upgrade context
     */
    MicronautServerEndpoint(WebSocketUpgradeContext context) {
        this.context = context;
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        WebSocketUpgradeContext ctx = context != null
            ? context
            : (WebSocketUpgradeContext) config.getUserProperties().get(CONTEXT_PROPERTY);
        if (ctx == null) {
            LOG.error("No Micronaut WebSocket context available for session [{}], closing", session.getId());
            closeQuietly(session, CloseReason.INTERNAL_ERROR);
            return;
        }
        this.context = ctx;
        ServletWebSocketSupport support = ctx.support();
        if (ctx.jakartaEndpoint() != null) {
            // The context is an implementation detail; user code sees the user properties
            // through Session.getUserProperties() and EndpointConfig.getUserProperties().
            config.getUserProperties().remove(CONTEXT_PROPERTY);
            session.getUserProperties().remove(CONTEXT_PROPERTY);
        }

        ConvertibleValues<Object> uriVariables = ConvertibleValues.of(ctx.routeMatch().getVariableValues());
        ServletWebSocketSession micronautSession = new ServletWebSocketSession(
            session,
            ctx.originatingRequest(),
            uriVariables,
            support.sessionRegistry(),
            support.encoder(),
            support.configuration().getMaxPendingSends()
        );
        if (open(session, config, support, ctx.webSocketBean(), ctx.jakartaEndpoint(), ctx.originatingRequest(), micronautSession)) {
            support.applicationContext().publishEvent(new WebSocketSessionOpenEvent(micronautSession));
        }
    }

    /**
     * Handlers run with the upgrade request propagated, so that
     * {@code ServerRequestContext.currentRequest()} resolves inside them.
     */
    @Override
    protected PropagatedContext propagatedContext() {
        return PropagatedContext.getOrEmpty().plus(new ServerHttpRequestContext(context.originatingRequest()));
    }

    @Override
    protected void sessionOpened(ServletWebSocketSession session) {
        support().sessionRegistry().register(session);
    }

    @Override
    protected void sessionClosed(ServletWebSocketSession session, boolean opened) {
        support().sessionRegistry().deregister(session);
        if (opened) {
            // A session that never reached the open event must not produce a close event,
            // or listeners see a close with no matching open.
            support().applicationContext().publishEvent(new WebSocketSessionClosedEvent(session));
        }
    }

    /**
     * Applies the payload limits. A Micronaut endpoint's one handler limits text and binary
     * alike; a Jakarta endpoint declares {@code maxMessageSize} per handler. A limit no handler
     * declares is the configured one.
     */
    @Override
    protected void applyLimits(Session session, @Nullable Integer declaredTextMax, @Nullable Integer declaredBinaryMax) {
        ServletWebSocketConfiguration configuration = support().configuration();
        session.setMaxTextMessageBufferSize(declaredTextMax != null ? declaredTextMax : configuration.getMaxTextMessageSize());
        session.setMaxBinaryMessageBufferSize(declaredBinaryMax != null ? declaredBinaryMax : configuration.getMaxBinaryMessageSize());
        session.setMaxIdleTimeout(support().idleTimeout().toMillis());
    }
}
