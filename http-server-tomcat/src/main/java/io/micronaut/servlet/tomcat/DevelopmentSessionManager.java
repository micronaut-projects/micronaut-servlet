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
package io.micronaut.servlet.tomcat;

import io.micronaut.core.annotation.Internal;
import io.micronaut.servlet.http.server.DevelopmentSessionStore;
import io.micronaut.servlet.http.server.DevelopmentSessionStore.SavedSession;
import org.apache.catalina.Context;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.session.StandardSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The session manager of a context of a generation of the application in development mode, in place of the
 * {@link StandardManager} Tomcat would create: as the context stops, it saves its sessions in the
 * {@link DevelopmentSessionStore} kept across restarts rather than in a file, and as the context of the next generation
 * starts, the manager of that one restores them, under the same ids, with their creation and last access times and
 * their maximum inactive intervals, deserializing their attributes with the class loader of the new generation.
 *
 * <p>Like the {@link StandardManager} that persists its sessions across a restart of Tomcat, it passivates each session
 * it saves, which tells the attributes that are {@link jakarta.servlet.http.HttpSessionActivationListener}s, and expires
 * it without telling the listeners, since the session goes on in the next generation; the restored session is activated
 * without telling the attribute listeners that its attributes were bound. A session it cannot restore is dropped, an
 * attribute that cannot be serialized or deserialized is dropped on its own.</p>
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
final class DevelopmentSessionManager extends StandardManager {

    private static final Logger LOG = LoggerFactory.getLogger(DevelopmentSessionManager.class);

    private final DevelopmentSessionStore store;
    private final ClassLoader classLoader;

    /**
     * @param store The sessions kept across restarts
     * @param classLoader The class loader of the generation, which deserializes the attributes
     */
    DevelopmentSessionManager(DevelopmentSessionStore store, ClassLoader classLoader) {
        this.store = store;
        this.classLoader = classLoader;
        // nothing is written to a file
        setPathname(null);
    }

    /**
     * Has the contexts of a server that has none of its own save and restore their sessions across restarts.
     *
     * @param contexts The contexts, not started
     * @param store The sessions kept across restarts
     * @param classLoader The class loader of the generation
     */
    static void install(Iterable<Context> contexts, DevelopmentSessionStore store, ClassLoader classLoader) {
        for (Context context : contexts) {
            if (context.getManager() == null && !context.getDistributable()) {
                context.setManager(new DevelopmentSessionManager(store, classLoader));
            }
        }
    }

    @Override
    public void load() {
        String deployment = deployment();
        long now = System.currentTimeMillis();
        for (SavedSession saved : store.sessions(deployment)) {
            store.remove(deployment, saved.id());
            try {
                if (findSession(saved.id()) != null || saved.isExpiredAt(now)) {
                    continue;
                }
                RestoredSession session = new RestoredSession(this);
                session.restore(saved, DevelopmentSessionStore.deserialize(saved, classLoader));
            } catch (Exception | LinkageError e) {
                LOG.debug("The session {} of {} cannot be restored", saved.id(), deployment, e);
            }
        }
    }

    @Override
    public void unload() {
        String deployment = deployment();
        for (Session session : findSessions()) {
            if (!(session instanceof StandardSession standard)) {
                continue;
            }
            try {
                if (!standard.isValid()) {
                    // expired meanwhile
                    continue;
                }
                standard.passivate();
                Map<String, Object> attributes = new LinkedHashMap<>();
                for (String name : Collections.list(standard.getAttributeNames())) {
                    attributes.put(name, standard.getAttribute(name));
                }
                store.save(deployment, SavedSession.of(
                    standard.getIdInternal(),
                    standard.getCreationTimeInternal(),
                    standard.getThisAccessedTimeInternal(),
                    standard.getMaxInactiveInterval(),
                    DevelopmentSessionStore.serialize(standard.getIdInternal(), attributes)
                ));
            } catch (RuntimeException e) {
                LOG.debug("The session {} of {} cannot be saved", session.getIdInternal(), deployment, e);
            }
            // it goes on in the next generation: the listeners are not told it expired
            try {
                standard.expire(false);
            } catch (RuntimeException e) {
                LOG.debug("Cannot expire the session {} of {}", session.getIdInternal(), deployment, e);
            }
        }
    }

    private String deployment() {
        Context context = getContext();
        return context == null ? "" : context.getName();
    }

    /**
     * A session restored with the times it was saved with, which a {@link StandardSession} only reads from its own
     * serialized form.
     */
    private static final class RestoredSession extends StandardSession {

        RestoredSession(Manager manager) {
            super(manager);
        }

        void restore(SavedSession saved, Map<String, Object> attributes) {
            setValid(true);
            setNew(false);
            setCreationTime(saved.creationTime());
            // the idle time counts from the last access before the restart
            lastAccessedTime = saved.lastAccessedTime();
            thisAccessedTime = saved.lastAccessedTime();
            setMaxInactiveInterval(saved.maxInactiveInterval());
            attributes.forEach((name, value) -> setAttribute(name, value, false));
            // adds it to the manager, without telling the listeners a session was created
            setId(saved.id(), false);
            activate();
        }
    }
}
