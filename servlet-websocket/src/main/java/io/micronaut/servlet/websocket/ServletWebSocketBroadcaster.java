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

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.MediaType;
import io.micronaut.websocket.WebSocketBroadcaster;
import io.micronaut.websocket.WebSocketSession;
import jakarta.inject.Singleton;
import jakarta.websocket.server.ServerContainer;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;

/**
 * A {@link WebSocketBroadcaster} that writes to every open session of this server.
 *
 * <p>Failures caused by a peer that has gone away are swallowed, matching the Netty
 * broadcaster which ignores {@code ClosedChannelException}.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
@Singleton
@Requires(classes = ServerContainer.class)
@Requires(beans = ServletWebSocketSessionRegistry.class)
public final class ServletWebSocketBroadcaster implements WebSocketBroadcaster {

    private final ServletWebSocketSessionRegistry sessionRegistry;

    /**
     * Default constructor.
     *
     * @param sessionRegistry The session registry
     */
    public ServletWebSocketBroadcaster(ServletWebSocketSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public <T> Publisher<T> broadcast(T message, MediaType mediaType, Predicate<WebSocketSession> filter) {
        return Mono.defer(() -> {
            List<CompletableFuture<?>> sends = new ArrayList<>();
            for (WebSocketSession session : sessionRegistry.getOpenSessions()) {
                if (!filter.test(session)) {
                    continue;
                }
                CompletableFuture<?> send;
                try {
                    send = session.sendAsync(message, mediaType);
                } catch (Exception e) {
                    send = CompletableFuture.failedFuture(e);
                }
                // A peer that went away during the broadcast is not a broadcast failure,
                // matching the Netty broadcaster which ignores ClosedChannelException.
                sends.add(send.exceptionally(error -> {
                    if (session.isOpen()) {
                        throw error instanceof CompletionException completion
                            ? completion
                            : new CompletionException(error);
                    }
                    return null;
                }));
            }
            if (sends.isEmpty()) {
                return Mono.just(message);
            }
            return Mono.fromCompletionStage(CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)))
                .thenReturn(message);
        });
    }
}
