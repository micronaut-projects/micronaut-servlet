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
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.Session;
import io.undertow.server.session.SessionListener;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.SessionPersistenceManager;
import jakarta.inject.Singleton;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps the container sessions of a generation's deployment across the restarts of the application in development
 * mode, through Undertow's own {@link SessionPersistenceManager}: as the deployment stops, Undertow passivates its
 * sessions and hands them to a persistence manager that saves them in the {@link DevelopmentSessionStore} kept across
 * restarts; as the deployment of the next generation starts, the attributes are deserialized with the class loader of
 * that deployment, the new generation's, and Undertow restores a session, under the same id, on its first request.
 *
 * <p>A session that is not requested before the next restart stays saved for the generation after: only a session the
 * deployment created, restored or new, is saved again from it. Undertow restores a session with a new creation time and
 * the deployment's default timeout, and does not restore one that expired. It undeploys a deployment by destroying its
 * sessions, which tells their listeners and their attributes that they are unbound.</p>
 *
 * <p>Only for a deployment that has no persistence manager of its own, and unless
 * {@value DevelopmentSessionStore#PERSIST_PROPERTY} is false.</p>
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@DevelopmentActive
@Requires(property = DevelopmentSessionStore.PERSIST_PROPERTY, notEquals = StringUtils.FALSE)
final class DevelopmentUndertowSessions implements BeanCreatedEventListener<DeploymentInfo> {

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
            String name = deployment.getDeploymentName();
            deployment.setSessionPersistenceManager(new Persistence(store));
            deployment.addSessionListener(new Restored(store, name));
        }
        return deployment;
    }

    /**
     * Saves the sessions of a deployment in the store, and reads them back for the next one.
     */
    private static final class Persistence implements SessionPersistenceManager {
        private final DevelopmentSessionStore store;

        Persistence(DevelopmentSessionStore store) {
            this.store = store;
        }

        @Override
        public void persistSessions(String deploymentName, Map<String, PersistentSession> sessionData) {
            sessionData.forEach((id, session) -> store.save(deploymentName, new SavedSession(
                id, -1, -1, -1, session.getExpiration().getTime(),
                DevelopmentSessionStore.serialize(id, session.getSessionData())
            )));
        }

        @Override
        public Map<String, PersistentSession> loadSessionAttributes(String deploymentName, ClassLoader classLoader) {
            Map<String, PersistentSession> sessions = new LinkedHashMap<>();
            for (SavedSession saved : store.sessions(deploymentName)) {
                sessions.put(saved.id(), new PersistentSession(
                    new Date(saved.expiresAt()),
                    DevelopmentSessionStore.deserialize(saved, classLoader)
                ));
            }
            return sessions;
        }

        @Override
        public void clear(String deploymentName) {
            // the sessions not restored yet stay saved for the next generation
        }
    }

    /**
     * Removes a saved session from the store once the deployment restored it, or created one under its id: from then
     * on, the deployment saves it again as it stops, if it is still live.
     */
    private static final class Restored implements SessionListener {
        private final DevelopmentSessionStore store;
        private final String deploymentName;

        Restored(DevelopmentSessionStore store, String deploymentName) {
            this.store = store;
            this.deploymentName = deploymentName;
        }

        @Override
        public void sessionCreated(Session session, HttpServerExchange exchange) {
            store.remove(deploymentName, session.getId());
        }

        @Override
        public void sessionIdChanged(Session session, String oldSessionId) {
            store.remove(deploymentName, oldSessionId);
        }
    }
}
