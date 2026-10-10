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
package io.micronaut.servlet.undertow;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.StringUtils;
import io.micronaut.servlet.http.server.DevelopmentSessionStore;
import io.micronaut.servlet.http.server.DevelopmentSessionStore.SavedSession;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.SecureRandomSessionIdGenerator;
import io.undertow.server.session.Session;
import io.undertow.server.session.SessionIdGenerator;
import io.undertow.server.session.SessionListener;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.SessionPersistenceManager;
import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.servlet.spec.HttpSessionImpl;
import io.undertow.servlet.spec.ServletContextImpl;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the container sessions of a generation's deployment across the restarts of the application in development
 * mode. As the deployment stops, Undertow passivates its sessions and hands them to a {@link SessionPersistenceManager}
 * that saves them in the {@link DevelopmentSessionStore} kept across restarts, with the times and the maximum inactive
 * interval of each. The deployment of the next generation restores a session on the first request that asks for it,
 * under the same id, with the maximum inactive interval it was saved with and its attributes deserialized with the class
 * loader of the deployment, the new generation's; a session that expired is not restored.
 *
 * <p>Undertow's own restore, which runs when a persistence manager loads sessions, would give the session a new id,
 * since a servlet deployment does not create a session under an id a client asks for: here the persistence manager
 * loads none, and a handler of the deployment restores the session, creating it under the saved id through the
 * deployment's session id generator. The restored session is new to the request that restores it, and has a new
 * creation time. A session that is not requested before the next restart stays saved for the generation after.
 * Undertow undeploys a deployment by destroying its sessions, which tells their listeners and their attributes that
 * they are unbound.</p>
 *
 * <p>Only for a deployment that has no persistence manager of its own, and unless
 * {@value DevelopmentSessionStore#PERSIST_PROPERTY} is false. Once no session saved for the deployment is left to
 * restore, its handler only checks a flag.</p>
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@DevelopmentActive
@Requires(property = DevelopmentSessionStore.PERSIST_PROPERTY, notEquals = StringUtils.FALSE)
final class DevelopmentUndertowSessions implements BeanCreatedEventListener<DeploymentInfo> {

    private static final Logger LOG = LoggerFactory.getLogger(DevelopmentUndertowSessions.class);
    /**
     * The id of the session a handler restores on this thread, which the deployment's generator hands the session.
     */
    private static final ThreadLocal<String> RESTORING = new ThreadLocal<>();

    private final DevelopmentSessionStore store;

    /**
     * @param store The container sessions kept across restarts
     */
    DevelopmentUndertowSessions(DevelopmentSessionStore store) {
        this.store = store;
    }

    @Override
    public DeploymentInfo onCreated(BeanCreatedEvent<DeploymentInfo> event) {
        DeploymentInfo deployment = event.getBean();
        if (deployment.getSessionPersistenceManager() == null) {
            SessionIdGenerator generator = deployment.getSessionIdGenerator();
            Sessions sessions = new Sessions(store, deployment.getDeploymentName(), generator != null ? generator : new SecureRandomSessionIdGenerator());
            deployment.setSessionPersistenceManager(sessions);
            deployment.addSessionListener(sessions);
            deployment.setSessionIdGenerator(sessions);
            deployment.addOuterHandlerChainWrapper(sessions);
        }
        return deployment;
    }

    /**
     * The sessions of a deployment: saved in the store as it stops, restored from it on the first request for each.
     */
    private static final class Sessions implements SessionPersistenceManager, SessionListener, SessionIdGenerator, HandlerWrapper {
        private final DevelopmentSessionStore store;
        private final String deploymentName;
        private final SessionIdGenerator generator;
        /**
         * The live sessions of the deployment, whose times and maximum inactive intervals Undertow does not hand the
         * persistence manager.
         */
        private final Map<String, Session> live = new ConcurrentHashMap<>();
        /**
         * Whether a session saved for the deployment may be left to restore.
         */
        private volatile boolean restoring;
        private volatile @Nullable ClassLoader classLoader;

        Sessions(DevelopmentSessionStore store, String deploymentName, SessionIdGenerator generator) {
            this.store = store;
            this.deploymentName = deploymentName;
            this.generator = generator;
        }

        @Override
        public void persistSessions(String name, Map<String, PersistentSession> sessionData) {
            sessionData.forEach((id, persistent) -> {
                Map<String, byte[]> attributes = DevelopmentSessionStore.serialize(id, persistent.getSessionData());
                Session session = live.get(id);
                SavedSession saved = null;
                if (session != null) {
                    try {
                        saved = SavedSession.of(id, session.getCreationTime(), session.getLastAccessedTime(), session.getMaxInactiveInterval(), attributes);
                    } catch (IllegalStateException e) {
                        // invalidated meanwhile
                    }
                }
                if (saved == null) {
                    saved = new SavedSession(id, -1, -1, SavedSession.UNKNOWN_INTERVAL, persistent.getExpiration().getTime(), Map.copyOf(attributes));
                }
                store.save(name, saved);
            });
        }

        @Override
        public Map<String, PersistentSession> loadSessionAttributes(String name, ClassLoader loader) {
            // the deployment starts: its handler restores the sessions saved for it, under their ids
            classLoader = loader;
            restoring = !store.sessions(name).isEmpty();
            return Map.of();
        }

        @Override
        public void clear(String name) {
            // the sessions not restored yet stay saved for the next generation
        }

        @Override
        public HttpHandler wrap(HttpHandler next) {
            return exchange -> {
                if (restoring) {
                    restore(exchange);
                }
                next.handleRequest(exchange);
            };
        }

        @Override
        public String createSessionId() {
            String restored = RESTORING.get();
            return restored != null ? restored : generator.createSessionId();
        }

        @Override
        public void sessionCreated(Session session, HttpServerExchange exchange) {
            live.put(session.getId(), session);
        }

        @Override
        public void sessionDestroyed(Session session, HttpServerExchange exchange, SessionDestroyedReason reason) {
            live.remove(session.getId());
        }

        @Override
        public void sessionIdChanged(Session session, String oldSessionId) {
            live.remove(oldSessionId);
            live.put(session.getId(), session);
            store.remove(deploymentName, oldSessionId);
        }

        /**
         * Restores the session the request asks for, if it was saved.
         */
        private void restore(HttpServerExchange exchange) {
            ServletRequestContext request = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
            ClassLoader loader = classLoader;
            if (request == null || loader == null) {
                return;
            }
            ServletContextImpl servletContext = request.getCurrentServletContext();
            String id = servletContext.getSessionConfig().findSessionId(exchange);
            if (id == null) {
                return;
            }
            SavedSession saved = store.remove(deploymentName, id);
            if (saved == null) {
                return;
            }
            if (store.sessions(deploymentName).isEmpty()) {
                restoring = false;
            }
            if (saved.isExpiredAt(System.currentTimeMillis()) || live.containsKey(id)) {
                return;
            }
            try {
                HttpSessionImpl session;
                RESTORING.set(id);
                try {
                    session = servletContext.getSession(exchange, true);
                } finally {
                    RESTORING.remove();
                }
                if (saved.maxInactiveInterval() != SavedSession.UNKNOWN_INTERVAL) {
                    session.setMaxInactiveInterval(saved.maxInactiveInterval());
                }
                Map<String, Object> attributes = DevelopmentSessionStore.deserialize(saved, loader);
                HttpSessionEvent event = new HttpSessionEvent(session);
                attributes.forEach((name, value) -> {
                    if (name.startsWith("io.undertow")) {
                        session.getSession().setAttribute(name, value);
                    } else {
                        session.setAttribute(name, value);
                    }
                });
                attributes.values().forEach(value -> {
                    if (value instanceof HttpSessionActivationListener listener) {
                        listener.sessionDidActivate(event);
                    }
                });
            } catch (RuntimeException e) {
                LOG.debug("The session {} of {} cannot be restored", id, deploymentName, e);
            }
        }
    }
}
