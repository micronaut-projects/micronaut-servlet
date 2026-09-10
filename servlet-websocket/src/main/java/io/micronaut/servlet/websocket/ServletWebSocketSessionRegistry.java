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

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.core.annotation.Internal;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Keeps track of the open WebSocket sessions of this server.
 *
 * <p>The Netty server tracks sessions in a single Netty {@code ChannelGroup} that spans
 * every endpoint, and both {@link WebSocketSession#getOpenSessions()} and the broadcaster
 * are built on it. This registry plays the same role, since the per-endpoint set offered
 * by {@code jakarta.websocket.Session#getOpenSessions()} has narrower scope.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
@Singleton
public final class ServletWebSocketSessionRegistry implements ApplicationEventListener<ServerShutdownEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(ServletWebSocketSessionRegistry.class);

    private final Set<ServletWebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    /**
     * Registers an open session.
     *
     * @param session The session
     */
    void register(ServletWebSocketSession session) {
        sessions.add(session);
    }

    /**
     * Removes a session that is no longer open.
     *
     * @param session The session
     */
    void deregister(ServletWebSocketSession session) {
        sessions.remove(session);
    }

    /**
     * @return The currently open sessions
     */
    public Set<? extends WebSocketSession> getOpenSessions() {
        return sessions.stream()
            .filter(WebSocketSession::isOpen)
            .collect(Collectors.collectingAndThen(Collectors.toSet(), Collections::unmodifiableSet));
    }

    /**
     * @return The registered sessions, whether open or not
     */
    Set<ServletWebSocketSession> getSessions() {
        return Collections.unmodifiableSet(sessions);
    }

    @Override
    public void onApplicationEvent(ServerShutdownEvent event) {
        for (ServletWebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.close(CloseReason.GOING_AWAY);
                }
            } catch (Exception e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error closing WebSocket session on shutdown: {}", e.getMessage(), e);
                }
            }
        }
        sessions.clear();
    }
}
