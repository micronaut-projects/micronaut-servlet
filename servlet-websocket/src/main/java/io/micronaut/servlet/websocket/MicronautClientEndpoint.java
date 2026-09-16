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
import io.micronaut.http.HttpRequest;
import io.micronaut.websocket.context.WebSocketBean;
import io.micronaut.websocket.exceptions.WebSocketSessionException;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;

import java.util.concurrent.CompletableFuture;

/**
 * The {@link jakarta.websocket.Endpoint} that adapts a connection the client opened onto a
 * Jakarta {@code @ClientEndpoint} bean.
 *
 * <p>The container is handed this endpoint, not the user's class, so it never scans or
 * instantiates anything: the dispatch is {@link AbstractMicronautEndpoint}'s, on the compiled
 * bean metadata. What a client connection has that a server one has not is the caller: the
 * handlers run under the context that was current when the connection was opened, and the
 * caller is told when {@code @OnOpen} has completed, since {@code connectToServer} returns the
 * session only then.</p>
 *
 * <p>A client session is not registered with the server's session registry and produces no
 * session events, as with the Netty client.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
final class MicronautClientEndpoint extends AbstractMicronautEndpoint {

    private final ServletWebSocketSupport support;
    private final WebSocketBean<Object> webSocketBean;
    private final JakartaEndpoint jakartaEndpoint;
    private final HttpRequest<?> handshakeRequest;
    private final PropagatedContext propagatedContext;
    private final ServletWebSocketSessionRegistry registry = new ServletWebSocketSessionRegistry();
    private final CompletableFuture<Void> opened = new CompletableFuture<>();

    /**
     * @param support           The shared infrastructure
     * @param webSocketBean     The endpoint bean
     * @param jakartaEndpoint   What the endpoint declares
     * @param handshakeRequest  The request the handlers are bound against
     * @param propagatedContext The context the connection was opened under
     */
    MicronautClientEndpoint(ServletWebSocketSupport support,
                            WebSocketBean<Object> webSocketBean,
                            JakartaEndpoint jakartaEndpoint,
                            HttpRequest<?> handshakeRequest,
                            PropagatedContext propagatedContext) {
        this.support = support;
        this.webSocketBean = webSocketBean;
        this.jakartaEndpoint = jakartaEndpoint;
        this.handshakeRequest = handshakeRequest;
        this.propagatedContext = propagatedContext;
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        ServletWebSocketSession micronautSession = new ServletWebSocketSession(
            session,
            handshakeRequest,
            ConvertibleValues.empty(),
            registry,
            support.encoder(),
            support.configuration().getMaxPendingSends()
        );
        if (!open(session, config, support, webSocketBean, jakartaEndpoint, handshakeRequest, micronautSession)) {
            opened.completeExceptionally(new WebSocketSessionException(
                "WebSocket client endpoint [" + webSocketBean.getBeanDefinition().getBeanType().getName() + "] could not be set up"));
        }
    }

    /**
     * Completes once {@code @OnOpen} has run, or fails with what went wrong, including the
     * session closing before it did.
     *
     * @return The completion of the open handler
     */
    CompletableFuture<Void> opened() {
        return opened;
    }

    @Override
    protected PropagatedContext propagatedContext() {
        return propagatedContext;
    }

    @Override
    protected void openCompleted() {
        opened.complete(null);
    }

    @Override
    protected void openFailed(Throwable cause) {
        opened.completeExceptionally(cause);
    }

    @Override
    protected void sessionClosed(ServletWebSocketSession session, boolean wasOpened) {
        opened.completeExceptionally(new WebSocketSessionException("WebSocket session closed before it was open"));
    }
}
