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
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.exceptions.WebSocketSessionException;
import jakarta.websocket.SendHandler;
import jakarta.websocket.Session;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Collection;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link WebSocketSession} backed by a {@link Session jakarta.websocket.Session}.
 *
 * <p>All outbound writes go through a single queue with at most one send outstanding on
 * the container. Jakarta containers reject a second asynchronous send while one is still
 * in flight, whereas Micronaut application code and the broadcaster may send from any
 * thread at any time.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
public final class ServletWebSocketSession implements WebSocketSession {

    private static final Logger LOG = LoggerFactory.getLogger(ServletWebSocketSession.class);
    private static final int MAX_CLOSE_REASON_BYTES = 123;

    private final Session session;
    private final HttpRequest<?> originatingRequest;
    private final ServletWebSocketSessionRegistry registry;
    private final ServletWebSocketMessageEncoder encoder;
    private final ConvertibleValues<Object> uriVariables;
    private final MutableConvertibleValues<Object> attributes;
    private final int maxPendingSends;

    private final Queue<PendingSend> sendQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingSends = new AtomicInteger();
    private final AtomicBoolean sending = new AtomicBoolean();

    /**
     * Default constructor.
     *
     * @param session            The Jakarta session
     * @param originatingRequest The HTTP request that produced the handshake
     * @param uriVariables       The URI template variables of the matched route
     * @param registry           The session registry
     * @param encoder            The message encoder
     * @param maxPendingSends    The queue depth beyond which the session reports it is not writable
     */
    @SuppressWarnings("unchecked")
    ServletWebSocketSession(Session session,
                            HttpRequest<?> originatingRequest,
                            ConvertibleValues<Object> uriVariables,
                            ServletWebSocketSessionRegistry registry,
                            ServletWebSocketMessageEncoder encoder,
                            int maxPendingSends) {
        this.session = session;
        this.originatingRequest = originatingRequest;
        this.uriVariables = uriVariables;
        this.registry = registry;
        this.encoder = encoder;
        this.maxPendingSends = maxPendingSends;
        this.attributes = originatingRequest
            .getAttribute("micronaut.SESSION", MutableConvertibleValues.class)
            .orElseGet(MutableConvertibleValuesMap::new);
    }

    /**
     * @return The underlying Jakarta WebSocket session
     */
    public Session getNativeSession() {
        return session;
    }

    @Override
    public String getId() {
        return session.getId();
    }

    @Override
    public MutableConvertibleValues<Object> getAttributes() {
        return attributes;
    }

    @Override
    public boolean isOpen() {
        return session.isOpen();
    }

    @Override
    public boolean isWritable() {
        return isOpen() && pendingSends.get() < maxPendingSends;
    }

    @Override
    public boolean isSecure() {
        return session.isSecure();
    }

    @Override
    public Set<? extends WebSocketSession> getOpenSessions() {
        return registry.getOpenSessions();
    }

    @Override
    public URI getRequestURI() {
        return originatingRequest.getUri();
    }

    @Override
    public String getProtocolVersion() {
        return session.getProtocolVersion();
    }

    @Override
    public Optional<String> getSubprotocol() {
        return Optional.ofNullable(session.getNegotiatedSubprotocol()).filter(StringUtils::isNotEmpty);
    }

    @Override
    public ConvertibleMultiValues<String> getRequestParameters() {
        return originatingRequest.getParameters();
    }

    @Override
    public ConvertibleValues<Object> getUriVariables() {
        return uriVariables;
    }

    @Override
    public Optional<Principal> getUserPrincipal() {
        Optional<Principal> fromRequest = originatingRequest.getUserPrincipal();
        if (fromRequest.isPresent()) {
            return fromRequest;
        }
        return Optional.ofNullable(session.getUserPrincipal());
    }

    @Override
    public <T> Publisher<T> send(T message, MediaType mediaType) {
        return Mono.defer(() -> Mono.fromCompletionStage(sendAsync(message, mediaType)));
    }

    @Override
    public <T> CompletableFuture<T> sendAsync(T message, MediaType mediaType) {
        if (message == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (!isOpen()) {
            throw new WebSocketSessionException("Session closed");
        }
        EncodedMessage encoded = encoder.encode(message, mediaType != null ? mediaType : MediaType.APPLICATION_JSON_TYPE);
        return enqueue(new PendingSend(encoded, false, new CompletableFuture<>()))
            .thenApply(ignored -> message);
    }

    @Override
    public CompletableFuture<?> sendPingAsync(byte[] content) {
        if (!isOpen()) {
            throw new WebSocketSessionException("Session closed");
        }
        ByteBuffer payload = ByteBuffer.wrap(content != null ? content : new byte[0]);
        return enqueue(new PendingSend(EncodedMessage.ofBinary(payload), true, new CompletableFuture<>()));
    }

    @Override
    public void close() {
        close(CloseReason.NORMAL);
    }

    @Override
    public void close(CloseReason closeReason) {
        registry.deregister(this);
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(toJakarta(closeReason));
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error closing WebSocket session [{}]: {}", getId(), e.getMessage(), e);
            }
        }
    }

    @Override
    public MutableConvertibleValues<Object> put(CharSequence key, @Nullable Object value) {
        attributes.put(key, value);
        return this;
    }

    @Override
    public MutableConvertibleValues<Object> remove(CharSequence key) {
        attributes.remove(key);
        return this;
    }

    @Override
    public MutableConvertibleValues<Object> clear() {
        attributes.clear();
        return this;
    }

    @Override
    public Set<String> names() {
        return attributes.names();
    }

    @Override
    public Collection<Object> values() {
        return attributes.values();
    }

    @Override
    public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
        return attributes.get(name, conversionContext);
    }

    @Override
    public String toString() {
        return "ServletWebSocketSession(" + getId() + ")";
    }

    private static jakarta.websocket.CloseReason toJakarta(CloseReason closeReason) {
        jakarta.websocket.CloseReason.CloseCode code;
        try {
            code = jakarta.websocket.CloseReason.CloseCodes.getCloseCode(closeReason.getCode());
        } catch (IllegalArgumentException e) {
            code = jakarta.websocket.CloseReason.CloseCodes.UNEXPECTED_CONDITION;
        }
        return new jakarta.websocket.CloseReason(code, truncateReason(closeReason.getReason()));
    }

    private static String truncateReason(@Nullable String reason) {
        if (reason == null) {
            return "";
        }
        if (reason.getBytes(StandardCharsets.UTF_8).length <= MAX_CLOSE_REASON_BYTES) {
            return reason;
        }
        // Cutting the encoded form at a fixed byte offset can split a multi byte sequence,
        // and the replacement character it decodes to is itself three bytes, which can push
        // the result back over the limit. Drop whole code points instead.
        int end = reason.length();
        while (end > 0) {
            end = reason.offsetByCodePoints(end, -1);
            String candidate = reason.substring(0, end);
            if (candidate.getBytes(StandardCharsets.UTF_8).length <= MAX_CLOSE_REASON_BYTES) {
                return candidate;
            }
        }
        return "";
    }

    private CompletableFuture<Void> enqueue(PendingSend pending) {
        sendQueue.add(pending);
        pendingSends.incrementAndGet();
        trySend();
        return pending.future();
    }

    private void trySend() {
        for (;;) {
            if (!sending.compareAndSet(false, true)) {
                return;
            }
            boolean asynchronous = false;
            for (;;) {
                PendingSend next = sendQueue.poll();
                if (next == null) {
                    break;
                }
                pendingSends.decrementAndGet();
                if (!dispatch(next)) {
                    asynchronous = true;
                    break;
                }
            }
            if (asynchronous) {
                // The send handler owns the queue until it fires.
                return;
            }
            sending.set(false);
            if (sendQueue.peek() == null) {
                return;
            }
        }
    }

    /**
     * Hands one queued message to the container.
     *
     * @param pending The message
     * @return {@code true} if the send already completed, meaning this thread may continue
     * draining, {@code false} if the send handler will resume draining when it fires
     */
    private boolean dispatch(PendingSend pending) {
        // 0 = dispatch in progress, 1 = handler completed while dispatching, 2 = dispatch returned
        AtomicInteger state = new AtomicInteger(0);
        SendHandler handler = result -> {
            complete(pending, result.isOK() ? null : result.getException());
            if (!state.compareAndSet(0, 1)) {
                sending.set(false);
                trySend();
            }
        };
        try {
            EncodedMessage message = pending.message();
            if (pending.ping()) {
                session.getAsyncRemote().sendPing(message.binary());
                pending.future().complete(null);
                return true;
            } else if (message.isText()) {
                session.getAsyncRemote().sendText(message.text(), handler);
            } else {
                session.getAsyncRemote().sendBinary(message.binary(), handler);
            }
        } catch (Throwable e) {
            pending.future().completeExceptionally(e);
            return true;
        }
        return !state.compareAndSet(0, 2);
    }

    private static void complete(PendingSend pending, @Nullable Throwable failure) {
        if (failure == null) {
            pending.future().complete(null);
        } else {
            pending.future().completeExceptionally(failure);
        }
    }

    /**
     * A queued outbound message.
     *
     * @param message The encoded message, or the ping payload when {@code ping} is set
     * @param ping    Whether this is a ping frame
     * @param future  Completed when the message has been written
     */
    private record PendingSend(EncodedMessage message, boolean ping, CompletableFuture<Void> future) {
    }
}
