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
package io.micronaut.servlet.http.server.jdk.websocket;

import io.micronaut.core.annotation.Internal;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Extension;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.CLOSE_ABNORMAL;
import static io.micronaut.servlet.http.server.jdk.websocket.WebSocketFrames.OPCODE_PONG;

/**
 * A {@link Session} over an upgraded exchange of the JDK server, so that the endpoints of
 * micronaut-servlet-websocket, and the Jakarta ones, run on it as they do on a servlet container.
 *
 * <p>The thread of the exchange opens the endpoint and then reads the connection until it ends,
 * delivering each message to the handler of its type on that thread, as a Jakarta container
 * delivers them. The writes of the connection are serialized, see {@link WebSocketConnection}.</p>
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
final class JdkWebSocketSession implements Session, WebSocketFrameDecoder.Listener {

    private static final Logger LOG = LoggerFactory.getLogger(JdkWebSocketSession.class);

    /**
     * How long the close frame of the client is waited for, once the server sent its own.
     */
    private static final long CLOSE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

    private final String id;
    private final JdkServerContainer container;
    private final Endpoint endpoint;
    private final ServerEndpointConfig config;
    private final Object endpointKey;
    private final WebSocketConnection connection;
    private final InputStream in;
    private final JdkHandshakeRequest request;
    private final String subprotocol;
    private final boolean secure;
    private final Map<String, Object> userProperties;
    private final RemoteEndpoint.Basic basicRemote;
    private final RemoteEndpoint.Async asyncRemote;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Object handlersLock = new Object();
    private final Object timerLock = new Object();
    private @Nullable Handler textHandler;
    private @Nullable Handler binaryHandler;
    private @Nullable Handler pongHandler;
    private volatile int maxTextMessageSize;
    private volatile int maxBinaryMessageSize;
    private volatile long maxIdleTimeout;
    /**
     * Why the session closed: the reason of the close the server sent first, or of the one the client did.
     */
    private final AtomicReference<@Nullable CloseReason> closeReason = new AtomicReference<>();
    private volatile boolean closeReceived;
    private @Nullable Future<?> idleCheck;
    private @Nullable List<Encoder> encoders;

    @SuppressWarnings("java:S107") // one parameter per part of the session; package-private
    JdkWebSocketSession(String id,
                        JdkServerContainer container,
                        Endpoint endpoint,
                        ServerEndpointConfig config,
                        Object endpointKey,
                        WebSocketConnection connection,
                        InputStream in,
                        JdkHandshakeRequest request,
                        String subprotocol) {
        this.id = id;
        this.container = container;
        this.endpoint = endpoint;
        this.config = config;
        this.endpointKey = endpointKey;
        this.connection = connection;
        this.in = in;
        this.request = request;
        this.subprotocol = subprotocol;
        this.secure = request.isSecure();
        // a copy, as a container makes it: what the session keeps is its own
        this.userProperties = new ConcurrentHashMap<>(config.getUserProperties());
        this.basicRemote = new JdkRemoteEndpoint.Basic(this, connection);
        this.asyncRemote = new JdkRemoteEndpoint.Async(this, connection, container.getDefaultAsyncSendTimeout());
        this.maxTextMessageSize = container.getDefaultMaxTextMessageBufferSize();
        this.maxBinaryMessageSize = container.getDefaultMaxBinaryMessageBufferSize();
        this.maxIdleTimeout = container.getDefaultMaxSessionIdleTimeout();
    }

    /**
     * Opens the endpoint, and reads the connection until it ends. Runs on the thread of the exchange.
     */
    void run() {
        CloseReason reason;
        try {
            scheduleIdleCheck();
            endpoint.onOpen(this, config);
            reason = read();
        } catch (RuntimeException e) {
            // the endpoint failed to open
            onError(e);
            closeQuietly(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Endpoint failed to open"));
            reason = closeReason.get();
        }
        finish(reason != null ? reason : new CloseReason(CloseReason.CloseCodes.CLOSED_ABNORMALLY, ""));
    }

    private @Nullable CloseReason read() {
        WebSocketFrameDecoder decoder = new WebSocketFrameDecoder(in, () -> maxTextMessageSize, () -> maxBinaryMessageSize);
        try {
            while (decoder.readFrame(this)) {
                connection.touch();
            }
            return closeReason.get();
        } catch (WebSocketProtocolException e) {
            LOG.debug("Closing WebSocket session [{}]: {}", id, e.getMessage());
            CloseReason reason = new CloseReason(CloseReason.CloseCodes.getCloseCode(e.getCloseCode()), e.getMessage());
            closeQuietly(reason);
            return reason;
        } catch (IOException e) {
            CloseReason closed = closeReason.get();
            if (closed != null && connection.isAborted()) {
                // the server dropped the connection after its close, e.g. waiting for the close of the client
                return closed;
            }
            if (!(e instanceof EOFException)) {
                onError(e);
            }
            return new CloseReason(CloseReason.CloseCodes.CLOSED_ABNORMALLY, "");
        }
    }

    private void finish(CloseReason reason) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        closing.set(true);
        cancelIdleCheck();
        container.removeSession(this);
        try {
            endpoint.onClose(this, reason);
        } catch (RuntimeException e) {
            LOG.warn("Error closing WebSocket session [{}]: {}", id, e.getMessage(), e);
        }
        destroyEncoders();
    }

    private void onError(Throwable error) {
        try {
            endpoint.onError(this, error);
        } catch (RuntimeException e) {
            LOG.warn("Error handling an error of WebSocket session [{}]: {}", id, e.getMessage(), e);
        }
    }

    @Override
    public void onText(String text) {
        Handler handler = handler(MessageKind.TEXT);
        if (handler == null) {
            closeQuietly(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "No handler for text messages"));
            return;
        }
        deliver(handler, handler.type() == Reader.class ? new StringReader(text) : text);
    }

    @Override
    public void onBinary(byte[] data) {
        Handler handler = handler(MessageKind.BINARY);
        if (handler == null) {
            closeQuietly(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "No handler for binary messages"));
            return;
        }
        Object message;
        if (handler.type() == byte[].class) {
            message = data;
        } else if (handler.type() == InputStream.class) {
            message = new ByteArrayInputStream(data);
        } else {
            message = ByteBuffer.wrap(data);
        }
        deliver(handler, message);
    }

    @Override
    public void onPing(byte[] data) throws IOException {
        if (!closeReceived && !connection.isCloseSent()) {
            connection.send(OPCODE_PONG, ByteBuffer.wrap(data));
        }
    }

    @Override
    public void onPong(byte[] data) {
        Handler handler = handler(MessageKind.PONG);
        if (handler != null) {
            deliver(handler, (PongMessage) () -> ByteBuffer.wrap(data).asReadOnlyBuffer());
        }
    }

    @Override
    public void onClose(int code, String reason) {
        closeReceived = true;
        closing.set(true);
        boolean echoed;
        try {
            // the close of the client is answered with its code, unless the server closed first
            echoed = connection.sendClose(WebSocketFrames.closePayload(code, reason));
        } catch (IOException e) {
            LOG.debug("Could not answer the close of WebSocket session [{}]: {}", id, e.getMessage());
            echoed = true;
        }
        CloseReason received = new CloseReason(CloseReason.CloseCodes.getCloseCode(code), reason);
        if (echoed) {
            closeReason.set(received);
        } else {
            closeReason.compareAndSet(null, received);
        }
    }

    @SuppressWarnings("unchecked")
    private void deliver(Handler handler, Object message) {
        try {
            if (handler.handler() instanceof MessageHandler.Partial<?> partial) {
                ((MessageHandler.Partial<Object>) partial).onMessage(message, true);
            } else {
                ((MessageHandler.Whole<Object>) handler.handler()).onMessage(message);
            }
        } catch (RuntimeException e) {
            onError(e);
        }
    }

    private @Nullable Handler handler(MessageKind kind) {
        synchronized (handlersLock) {
            return switch (kind) {
                case TEXT -> textHandler;
                case BINARY -> binaryHandler;
                case PONG -> pongHandler;
            };
        }
    }

    @Override
    public WebSocketContainer getContainer() {
        return container;
    }

    @Override
    public void addMessageHandler(MessageHandler handler) {
        Class<?> type = messageType(handler.getClass());
        if (type == null) {
            throw new IllegalStateException("Cannot tell the message type of handler " + handler.getClass().getName());
        }
        addHandler(type, handler);
    }

    @Override
    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler) {
        addHandler(clazz, handler);
    }

    @Override
    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler) {
        addHandler(clazz, handler);
    }

    private void addHandler(Class<?> type, MessageHandler handler) {
        MessageKind kind = MessageKind.of(type, handler instanceof MessageHandler.Partial);
        Handler added = new Handler(type, handler);
        synchronized (handlersLock) {
            switch (kind) {
                case TEXT -> textHandler = checkFree(textHandler, kind, added);
                case BINARY -> binaryHandler = checkFree(binaryHandler, kind, added);
                default -> pongHandler = checkFree(pongHandler, kind, added);
            }
        }
    }

    private static Handler checkFree(@Nullable Handler existing, MessageKind kind, Handler added) {
        if (existing != null) {
            throw new IllegalStateException("A handler of " + kind.name().toLowerCase() + " messages is registered already");
        }
        return added;
    }

    /**
     * The type of messages a handler registered without one takes, from the type argument of
     * the {@link MessageHandler.Whole} or {@link MessageHandler.Partial} it implements.
     */
    private static @Nullable Class<?> messageType(Class<?> handlerClass) {
        for (Class<?> type = handlerClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Type implemented : type.getGenericInterfaces()) {
                if (implemented instanceof ParameterizedType parameterized
                    && (parameterized.getRawType() == MessageHandler.Whole.class || parameterized.getRawType() == MessageHandler.Partial.class)) {
                    Type argument = parameterized.getActualTypeArguments()[0];
                    if (argument instanceof Class<?> messageType) {
                        return messageType;
                    }
                    if (argument instanceof ParameterizedType parameterizedArgument && parameterizedArgument.getRawType() instanceof Class<?> raw) {
                        return raw;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public Set<MessageHandler> getMessageHandlers() {
        Set<MessageHandler> handlers = new HashSet<>();
        synchronized (handlersLock) {
            for (Handler handler : new Handler[] {textHandler, binaryHandler, pongHandler}) {
                if (handler != null) {
                    handlers.add(handler.handler());
                }
            }
        }
        return handlers;
    }

    @Override
    public void removeMessageHandler(MessageHandler handler) {
        synchronized (handlersLock) {
            if (textHandler != null && textHandler.handler() == handler) {
                textHandler = null;
            }
            if (binaryHandler != null && binaryHandler.handler() == handler) {
                binaryHandler = null;
            }
            if (pongHandler != null && pongHandler.handler() == handler) {
                pongHandler = null;
            }
        }
    }

    @Override
    public String getProtocolVersion() {
        return WebSocketHandshake.VERSION;
    }

    @Override
    public String getNegotiatedSubprotocol() {
        return subprotocol;
    }

    @Override
    public List<Extension> getNegotiatedExtensions() {
        return List.of();
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public boolean isOpen() {
        return !closing.get();
    }

    @Override
    public long getMaxIdleTimeout() {
        return maxIdleTimeout;
    }

    @Override
    public void setMaxIdleTimeout(long milliseconds) {
        this.maxIdleTimeout = milliseconds;
        scheduleIdleCheck();
    }

    @Override
    public void setMaxBinaryMessageBufferSize(int length) {
        this.maxBinaryMessageSize = length;
    }

    @Override
    public int getMaxBinaryMessageBufferSize() {
        return maxBinaryMessageSize;
    }

    @Override
    public void setMaxTextMessageBufferSize(int length) {
        this.maxTextMessageSize = length;
    }

    @Override
    public int getMaxTextMessageBufferSize() {
        return maxTextMessageSize;
    }

    @Override
    public RemoteEndpoint.Async getAsyncRemote() {
        return asyncRemote;
    }

    @Override
    public RemoteEndpoint.Basic getBasicRemote() {
        return basicRemote;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void close() throws IOException {
        close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, ""));
    }

    @Override
    public void close(CloseReason closeReason) throws IOException {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        this.closeReason.set(closeReason);
        int code = closeReason.getCloseCode().getCode();
        if (code == CLOSE_ABNORMAL || code == CloseReason.CloseCodes.TLS_HANDSHAKE_FAILURE.getCode()) {
            // these never go on the wire: the connection is just dropped
            connection.abort();
            return;
        }
        // the deadline runs whether or not the close frame can be written: a write blocked on a peer
        // that reads nothing holds the connection, and only dropping it ends that write
        Future<?> deadline = closeReceived ? null : container.schedule(() -> {
            if (!finished.get()) {
                connection.abort();
            }
        }, CLOSE_TIMEOUT_MILLIS);
        try {
            connection.sendClose(WebSocketFrames.closePayload(code, closeReason.getReasonPhrase()));
        } catch (IOException e) {
            if (deadline != null) {
                deadline.cancel(false);
            }
            connection.abort();
            throw e;
        }
    }

    private void closeQuietly(CloseReason reason) {
        try {
            close(reason);
        } catch (IOException | RuntimeException e) {
            LOG.debug("Error closing WebSocket session [{}]: {}", id, e.getMessage());
        }
    }

    /**
     * Closes the session as the server stops.
     */
    void goingAway() {
        closeQuietly(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Server stopping"));
    }

    /**
     * @return Whether the close frame of the server went out
     */
    boolean isCloseSent() {
        return connection.isCloseSent();
    }

    /**
     * Drops the connection.
     */
    void abort() {
        connection.abort();
    }

    private void scheduleIdleCheck() {
        synchronized (timerLock) {
            cancelIdleCheck();
            long timeout = maxIdleTimeout;
            if (timeout > 0 && !finished.get()) {
                idleCheck = container.schedule(this::checkIdle, timeout);
            }
        }
    }

    private void checkIdle() {
        long timeout = maxIdleTimeout;
        if (timeout <= 0 || finished.get()) {
            return;
        }
        long idle = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connection.lastActivity());
        if (idle >= timeout) {
            LOG.debug("Closing idle WebSocket session [{}]", id);
            closeQuietly(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Idle timeout"));
            return;
        }
        synchronized (timerLock) {
            if (!finished.get()) {
                idleCheck = container.schedule(this::checkIdle, timeout - idle);
            }
        }
    }

    private void cancelIdleCheck() {
        synchronized (timerLock) {
            Future<?> check = idleCheck;
            idleCheck = null;
            if (check != null) {
                check.cancel(false);
            }
        }
    }

    @Override
    public URI getRequestURI() {
        return request.getRequestURI();
    }

    @Override
    public Map<String, List<String>> getRequestParameterMap() {
        return request.getParameterMap();
    }

    @Override
    public String getQueryString() {
        return request.getQueryString();
    }

    @Override
    public Map<String, String> getPathParameters() {
        return request.getPathParameters();
    }

    @Override
    public Map<String, Object> getUserProperties() {
        return userProperties;
    }

    @Override
    public @Nullable Principal getUserPrincipal() {
        return request.getUserPrincipal();
    }

    @Override
    public Set<Session> getOpenSessions() {
        return container.openSessions(endpointKey);
    }

    /**
     * @return What identifies the endpoint the session was opened on, for {@link #getOpenSessions()}
     */
    Object getEndpointKey() {
        return endpointKey;
    }

    /**
     * Runs a task later, off the timer thread.
     *
     * @param task        The task
     * @param delayMillis In how long
     * @return Its future, to cancel it
     */
    Future<?> schedule(Runnable task, long delayMillis) {
        return container.schedule(task, delayMillis);
    }

    /**
     * Encodes an object with the encoders of the endpoint, for {@code sendObject}.
     *
     * @param data The object
     * @return A {@link ByteBuffer} for a binary message, or the text of a text message
     * @throws EncodeException If no encoder takes it, or the one that does failed
     */
    Object encode(Object data) throws EncodeException {
        for (Encoder encoder : encoders()) {
            Method encode = encodeMethod(encoder, data);
            if (encode == null) {
                continue;
            }
            try {
                return switch (encoder) {
                    case Encoder.Text<?> _ -> encode.invoke(encoder, data);
                    case Encoder.Binary<?> _ -> encode.invoke(encoder, data);
                    case Encoder.TextStream<?> _ -> {
                        StringWriter writer = new StringWriter();
                        encode.invoke(encoder, data, writer);
                        yield writer.toString();
                    }
                    default -> {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        encode.invoke(encoder, data, out);
                        yield ByteBuffer.wrap(out.toByteArray());
                    }
                };
            } catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof EncodeException encodeException) {
                    throw encodeException;
                }
                throw new EncodeException(data, "Encoder " + encoder.getClass().getName() + " failed", cause);
            }
        }
        if (data.getClass().isPrimitive() || data instanceof Number || data instanceof Boolean || data instanceof Character) {
            return data.toString();
        }
        throw new EncodeException(data, "No encoder for " + data.getClass().getName());
    }

    private static @Nullable Method encodeMethod(Encoder encoder, Object data) {
        for (Method method : encoder.getClass().getMethods()) {
            if (method.getName().equals("encode") && !method.isBridge() && !Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() >= 1 && method.getParameterTypes()[0].isInstance(data)) {
                return method;
            }
        }
        return null;
    }

    private synchronized List<Encoder> encoders() {
        if (encoders == null) {
            List<Encoder> created = new ArrayList<>();
            for (Class<? extends Encoder> type : config.getEncoders()) {
                try {
                    Encoder encoder = type.getDeclaredConstructor().newInstance();
                    encoder.init(config);
                    created.add(encoder);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOG.warn("Cannot create the WebSocket encoder [{}]: {}", type.getName(), e.getMessage());
                }
            }
            encoders = created;
        }
        return encoders;
    }

    private synchronized void destroyEncoders() {
        if (encoders != null) {
            for (Encoder encoder : encoders) {
                try {
                    encoder.destroy();
                } catch (RuntimeException e) {
                    LOG.debug("Error destroying the WebSocket encoder [{}]: {}", encoder.getClass().getName(), e.getMessage());
                }
            }
            encoders = null;
        }
    }

    @Override
    public String toString() {
        return "JdkWebSocketSession[" + id + "]";
    }

    /**
     * A registered message handler and the type of message it takes.
     *
     * @param type    The type of message
     * @param handler The handler
     */
    private record Handler(Class<?> type, MessageHandler handler) {
    }

    /**
     * The kinds of message a session has one handler for each of.
     */
    private enum MessageKind {
        TEXT, BINARY, PONG;

        static MessageKind of(Class<?> type, boolean partial) {
            if (type == String.class || (!partial && type == Reader.class)) {
                return TEXT;
            }
            if (type == ByteBuffer.class || type == byte[].class || (!partial && type == InputStream.class)) {
                return BINARY;
            }
            if (type == PongMessage.class && !partial) {
                return PONG;
            }
            throw new IllegalStateException("Messages of type " + type.getName() + " are not supported: "
                + "a handler takes String, Reader, ByteBuffer, byte[], InputStream or PongMessage");
        }
    }
}
