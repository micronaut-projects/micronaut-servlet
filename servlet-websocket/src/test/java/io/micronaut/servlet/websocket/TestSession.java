package io.micronaut.servlet.websocket;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Extension;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal {@link Session} double that records what was sent and lets a test decide when
 * each send completes, so the send queue can be driven deterministically.
 */
final class TestSession implements Session {

    final Deque<String> sentText = new ArrayDeque<>();
    final Deque<ByteBuffer> sentBinary = new ArrayDeque<>();
    final Deque<ByteBuffer> sentPings = new ArrayDeque<>();
    final Deque<SendHandler> pending = new ArrayDeque<>();
    final AtomicInteger inFlight = new AtomicInteger();
    final Map<String, Object> userProperties = new HashMap<>();

    CloseReason closeReason;
    boolean open = true;
    int maxTextMessageBufferSize;
    int maxBinaryMessageBufferSize;
    long maxIdleTimeout;
    private String id = "test-session";

    /**
     * Completes the oldest outstanding send successfully.
     *
     * @return whether there was a send to complete
     */
    boolean completeOldest() {
        SendHandler handler = pending.poll();
        if (handler == null) {
            return false;
        }
        inFlight.decrementAndGet();
        handler.onResult(new SendResult());
        return true;
    }

    /**
     * Fails the oldest outstanding send.
     *
     * @param failure The failure
     */
    void failOldest(Throwable failure) {
        SendHandler handler = pending.poll();
        if (handler == null) {
            return;
        }
        inFlight.decrementAndGet();
        handler.onResult(new SendResult(failure));
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public void close() {
        close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, ""));
    }

    @Override
    public void close(CloseReason closeReason) {
        this.closeReason = closeReason;
        this.open = false;
    }

    @Override
    public String getProtocolVersion() {
        return "13";
    }

    @Override
    public String getNegotiatedSubprotocol() {
        return "";
    }

    @Override
    public void setMaxTextMessageBufferSize(int max) {
        this.maxTextMessageBufferSize = max;
    }

    @Override
    public int getMaxTextMessageBufferSize() {
        return maxTextMessageBufferSize;
    }

    @Override
    public void setMaxBinaryMessageBufferSize(int max) {
        this.maxBinaryMessageBufferSize = max;
    }

    @Override
    public int getMaxBinaryMessageBufferSize() {
        return maxBinaryMessageBufferSize;
    }

    @Override
    public void setMaxIdleTimeout(long timeout) {
        this.maxIdleTimeout = timeout;
    }

    @Override
    public long getMaxIdleTimeout() {
        return maxIdleTimeout;
    }

    @Override
    public RemoteEndpoint.Async getAsyncRemote() {
        return new AsyncRemote();
    }

    @Override
    public Map<String, Object> getUserProperties() {
        return userProperties;
    }

    @Override
    public Set<Session> getOpenSessions() {
        return Set.of();
    }

    @Override
    public WebSocketContainer getContainer() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addMessageHandler(MessageHandler handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<MessageHandler> getMessageHandlers() {
        return Set.of();
    }

    @Override
    public void removeMessageHandler(MessageHandler handler) {
        // no-op
    }

    @Override
    public List<Extension> getNegotiatedExtensions() {
        return List.of();
    }

    @Override
    public RemoteEndpoint.Basic getBasicRemote() {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI getRequestURI() {
        return URI.create("/test");
    }

    @Override
    public Map<String, List<String>> getRequestParameterMap() {
        return Map.of();
    }

    @Override
    public String getQueryString() {
        return "";
    }

    @Override
    public Map<String, String> getPathParameters() {
        return Map.of();
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    private final class AsyncRemote implements RemoteEndpoint.Async {

        @Override
        public void sendText(String text, SendHandler handler) {
            sentText.add(text);
            inFlight.incrementAndGet();
            pending.add(handler);
        }

        @Override
        public void sendBinary(ByteBuffer data, SendHandler handler) {
            sentBinary.add(data);
            inFlight.incrementAndGet();
            pending.add(handler);
        }

        @Override
        public void sendPing(ByteBuffer applicationData) {
            sentPings.add(applicationData);
        }

        @Override
        public void sendPong(ByteBuffer applicationData) {
            // no-op
        }

        @Override
        public long getSendTimeout() {
            return 0;
        }

        @Override
        public void setSendTimeout(long timeout) {
            // no-op
        }

        @Override
        public Future<Void> sendText(String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> sendBinary(ByteBuffer data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> sendObject(Object data) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void sendObject(Object data, SendHandler handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setBatchingAllowed(boolean batchingAllowed) throws IOException {
            // no-op
        }

        @Override
        public boolean getBatchingAllowed() {
            return false;
        }

        @Override
        public void flushBatch() throws IOException {
            // no-op
        }
    }
}
