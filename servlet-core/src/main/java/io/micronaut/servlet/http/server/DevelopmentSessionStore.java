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
package io.micronaut.servlet.http.server;

import io.micronaut.context.annotation.Retain;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.core.annotation.Internal;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The container sessions of the application in development mode, kept across its restarts: as a generation stops, its
 * container saves each live session here, with the serialized form of each attribute, and the next generation's
 * container restores them, under the same session id, deserializing the attributes with the class loader of the new
 * generation. It holds bytes and JDK types only, so that no class of a retired generation stays reachable from it.
 *
 * <p>An attribute that is not {@link Serializable}, or fails to serialize, is not saved, and one that fails to
 * deserialize, because its class changed incompatibly or is gone, is not restored: each is dropped on its own, with a
 * debug log, and the rest of the session is kept. A session that expired, from its last access and its maximum
 * inactive interval, is not restored.</p>
 *
 * <p>On by default in development mode; {@value #PERSIST_PROPERTY} set to {@code false} turns it off, and each
 * generation then starts with no sessions, as in production. Only development code of the containers uses it: nothing
 * is saved or restored but as a generation stops or starts.</p>
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@Retain
@DevelopmentActive
public final class DevelopmentSessionStore {

    /**
     * Whether the container sessions survive the restarts of the application in development mode, true by default.
     */
    public static final String PERSIST_PROPERTY = "micronaut.servlet.dev.sessions.persist";

    private static final Logger LOG = LoggerFactory.getLogger(DevelopmentSessionStore.class);

    /**
     * The saved sessions, by deployment, then by session id.
     */
    private final Map<String, Map<String, SavedSession>> deployments = new ConcurrentHashMap<>();

    /**
     * Created once, by the first generation that uses it, and kept from then on.
     */
    DevelopmentSessionStore() {
    }

    /**
     * Saves a session of a deployment, replacing any saved under its id, unless it expired already.
     *
     * @param deployment The deployment, as its container names it, such as the context path
     * @param session The session
     */
    public void save(String deployment, SavedSession session) {
        if (session.isExpiredAt(System.currentTimeMillis())) {
            return;
        }
        deployments.computeIfAbsent(deployment, name -> new ConcurrentHashMap<>()).put(session.id(), session);
    }

    /**
     * The sessions saved for a deployment that have not expired, which stay saved until {@link #remove(String, String)}
     * removes them; those that expired are removed.
     *
     * @param deployment The deployment
     * @return The sessions
     */
    public Collection<SavedSession> sessions(String deployment) {
        Map<String, SavedSession> saved = deployments.get(deployment);
        if (saved == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        saved.values().removeIf(session -> session.isExpiredAt(now));
        return new ArrayList<>(saved.values());
    }

    /**
     * Removes a saved session: it was restored, or is not to be.
     *
     * @param deployment The deployment
     * @param id The session id
     */
    public void remove(String deployment, String id) {
        Map<String, SavedSession> saved = deployments.get(deployment);
        if (saved != null) {
            saved.remove(id);
        }
    }

    /**
     * The serialized form of the attributes of a session, without those that cannot be serialized.
     *
     * @param id The session id, for the log
     * @param attributes The attributes
     * @return The serialized attributes
     */
    public static Map<String, byte[]> serialize(String id, Map<String, ?> attributes) {
        Map<String, byte[]> serialized = new LinkedHashMap<>();
        attributes.forEach((name, value) -> {
            if (value == null) {
                return;
            }
            if (!(value instanceof Serializable)) {
                LOG.debug("The attribute {} of the session {} is a {}, which is not serializable: it is not kept across the restart", name, id, value.getClass().getName());
                return;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(value);
            } catch (IOException | RuntimeException e) {
                LOG.debug("The attribute {} of the session {} cannot be serialized: it is not kept across the restart", name, id, e);
                return;
            }
            serialized.put(name, bytes.toByteArray());
        });
        return serialized;
    }

    /**
     * The attributes of a saved session, deserialized with the given class loader, without those that cannot be.
     *
     * @param session The session
     * @param classLoader The class loader of the generation that restores it
     * @return The attributes
     */
    public static Map<String, Object> deserialize(SavedSession session, ClassLoader classLoader) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        session.attributes().forEach((name, bytes) -> {
            try (ObjectInputStream in = new GenerationObjectInputStream(new ByteArrayInputStream(bytes), classLoader)) {
                attributes.put(name, in.readObject());
            } catch (IOException | ClassNotFoundException | RuntimeException | LinkageError e) {
                LOG.debug("The attribute {} of the session {} cannot be deserialized, its class may have changed: it is not restored", name, session.id(), e);
            }
        });
        return attributes;
    }

    /**
     * A session saved as a generation stopped.
     *
     * @param id The session id
     * @param creationTime When it was created, in milliseconds since the epoch, or -1 when the container does not say
     * @param lastAccessedTime When it was last accessed, in milliseconds since the epoch, or -1 when the container does
     *                         not say
     * @param maxInactiveInterval Its maximum inactive interval, in seconds, not positive when it never expires
     * @param expiresAt When it expires, in milliseconds since the epoch, or {@link Long#MAX_VALUE} for never
     * @param attributes Its serialized attributes
     */
    public record SavedSession(String id,
                               long creationTime,
                               long lastAccessedTime,
                               int maxInactiveInterval,
                               long expiresAt,
                               Map<String, byte[]> attributes) {

        /**
         * A session that expires after its maximum inactive interval past its last access.
         *
         * @param id The session id
         * @param creationTime When it was created
         * @param lastAccessedTime When it was last accessed
         * @param maxInactiveInterval Its maximum inactive interval, in seconds, not positive when it never expires
         * @param attributes Its serialized attributes
         * @return The session
         */
        public static SavedSession of(String id, long creationTime, long lastAccessedTime, int maxInactiveInterval, Map<String, byte[]> attributes) {
            long expiresAt = maxInactiveInterval > 0 ? lastAccessedTime + maxInactiveInterval * 1000L : Long.MAX_VALUE;
            return new SavedSession(id, creationTime, lastAccessedTime, maxInactiveInterval, expiresAt, Map.copyOf(attributes));
        }

        /**
         * @param now The time, in milliseconds since the epoch
         * @return Whether the session expired at that time
         */
        public boolean isExpiredAt(long now) {
            return expiresAt <= now;
        }
    }

    /**
     * Resolves the classes of a serialized attribute with the class loader of the generation that restores it, rather
     * than with the loader of the caller, which is the library's and does not see the application's classes.
     */
    private static final class GenerationObjectInputStream extends ObjectInputStream {
        private final ClassLoader classLoader;

        GenerationObjectInputStream(InputStream in, ClassLoader classLoader) throws IOException {
            super(in);
            this.classLoader = classLoader;
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            try {
                return Class.forName(desc.getName(), false, classLoader);
            } catch (ClassNotFoundException e) {
                // primitive types and arrays of them
                return super.resolveClass(desc);
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        protected Class<?> resolveProxyClass(String[] interfaces) throws IOException, ClassNotFoundException {
            Class<?>[] resolved = new Class<?>[interfaces.length];
            for (int i = 0; i < interfaces.length; i++) {
                resolved[i] = Class.forName(interfaces[i], false, classLoader);
            }
            try {
                return Proxy.getProxyClass(classLoader, resolved);
            } catch (IllegalArgumentException e) {
                throw new ClassNotFoundException("Cannot define a proxy of " + String.join(", ", interfaces), e);
            }
        }
    }
}
