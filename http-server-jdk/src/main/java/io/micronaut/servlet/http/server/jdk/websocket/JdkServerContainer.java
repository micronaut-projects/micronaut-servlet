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

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.servlet.websocket.WebSocketUpgradeContext;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Extension;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * The Jakarta WebSocket {@link ServerContainer} of the JDK server, which upgrades a handshake
 * the Micronaut filter chain approved: the WebSocket support of micronaut-servlet-websocket
 * upgrades through it as it does through the container of Jetty, Tomcat or Undertow, so that
 * {@code @ServerWebSocket} beans, the WebSocket routes of the route builder and Jakarta endpoints
 * work alike.
 *
 * <p>The server has no protocol upgrade API, so the upgrade relies on a
 * {@code com.sun.net.httpserver} provider that leaves the streams of the exchange as those of the
 * connection after a {@code 101 Switching Protocols}, see {@link JdkWebSocketCapability}. Without
 * one a handshake is answered with {@code 501 Not Implemented}. The protocol is spoken by
 * {@link WebSocketFrameDecoder} and {@link WebSocketFrames}, without extensions.</p>
 *
 * <p>Endpoints cannot be deployed to it, Micronaut routes the handshakes, and it opens no client
 * connections.</p>
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
@Singleton
@Requires(classes = ServerContainer.class)
public final class JdkServerContainer implements ServerContainer {

    private static final Logger LOG = LoggerFactory.getLogger(JdkServerContainer.class);
    /**
     * The send timeout of micronaut-servlet-websocket, which the servlet containers are given by
     * their initializers.
     */
    private static final String ASYNC_SEND_TIMEOUT = "micronaut.servlet.websocket.async-send-timeout";
    private static final int DEFAULT_MAX_MESSAGE_SIZE = 65536;
    /**
     * How long the sessions are given to send their close as the server stops.
     */
    private static final long SHUTDOWN_CLOSE_MILLIS = 1000;
    private static final boolean UPGRADE_CONTEXT_PRESENT = ClassUtils.isPresent(
        "io.micronaut.servlet.websocket.WebSocketUpgradeContext", JdkServerContainer.class.getClassLoader());
    /**
     * The headers of the handshake response that are the server's, which the configurator
     * cannot set.
     */
    private static final Set<String> HANDSHAKE_HEADERS = Set.of(
        "connection", "upgrade", "content-length", "transfer-encoding",
        "sec-websocket-accept", "sec-websocket-protocol", "sec-websocket-extensions", "sec-websocket-version"
    );

    private final JdkWebSocketCapability capability;
    private final Set<JdkWebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicLong ids = new AtomicLong();
    private final Object schedulerLock = new Object();
    private @Nullable ScheduledExecutorService scheduler;
    private @Nullable ExecutorService workers;
    private volatile long asyncSendTimeout;
    private volatile long maxSessionIdleTimeout;
    private volatile int maxBinaryMessageBufferSize = DEFAULT_MAX_MESSAGE_SIZE;
    private volatile int maxTextMessageBufferSize = DEFAULT_MAX_MESSAGE_SIZE;

    /**
     * @param capability    Whether a handshake can be upgraded
     * @param configuration    The server configuration, for the default idle timeout
     * @param asyncSendTimeout The default send timeout of {@code RemoteEndpoint.Async}, if any
     */
    JdkServerContainer(JdkWebSocketCapability capability,
                       HttpServerConfiguration configuration,
                       @Property(name = ASYNC_SEND_TIMEOUT) @Nullable Duration asyncSendTimeout) {
        this.capability = capability;
        this.maxSessionIdleTimeout = configuration.getIdleTimeout().toMillis();
        this.asyncSendTimeout = asyncSendTimeout != null ? asyncSendTimeout.toMillis() : 0;
    }

    @Override
    public void upgradeHttpToWebSocket(Object httpServletRequest,
                                       Object httpServletResponse,
                                       ServerEndpointConfig sec,
                                       Map<String, String> pathParameters) throws IOException, DeploymentException {
        if (!capability.isSupported()) {
            throw new HttpStatusException(HttpStatus.NOT_IMPLEMENTED, capability.unsupportedMessage());
        }
        if (!(httpServletRequest instanceof UpgradableExchange upgradable)) {
            throw new DeploymentException("Not a request of the built-in JDK HTTP server: " + httpServletRequest);
        }
        HttpExchange exchange = upgradable.getHttpExchange();
        JdkHandshakeRequest request = new JdkHandshakeRequest(exchange, pathParameters);
        String key = validate(request, httpServletResponse);
        ServerEndpointConfig.Configurator configurator = sec.getConfigurator();
        if (!configurator.checkOrigin(request.header("Origin"))) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "WebSocket origin not allowed");
        }
        String subprotocol = negotiateSubprotocol(configurator, sec, request);
        Map<String, List<String>> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        configurator.modifyHandshake(sec, request, (HandshakeResponse) () -> responseHeaders);
        Endpoint endpoint = endpoint(configurator, sec);

        Headers headers = exchange.getResponseHeaders();
        responseHeaders.forEach((name, values) -> {
            if (!HANDSHAKE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, new ArrayList<>(values));
            }
        });
        headers.set("Upgrade", "websocket");
        headers.set("Connection", "Upgrade");
        headers.set(HandshakeResponse.SEC_WEBSOCKET_ACCEPT, WebSocketHandshake.accept(key));
        if (!subprotocol.isEmpty()) {
            headers.set(HandshakeRequest.SEC_WEBSOCKET_PROTOCOL, subprotocol);
        }
        exchange.sendResponseHeaders(HttpStatus.SWITCHING_PROTOCOLS.getCode(), 0);
        OutputStream out = exchange.getResponseBody();
        out.flush();
        // taken after the 101: the provider only then gives the stream of the connection
        InputStream in = exchange.getRequestBody();
        WebSocketConnection connection = new WebSocketConnection(out, workers(), exchange::close);
        JdkWebSocketSession session = new JdkWebSocketSession(
            Long.toHexString(ids.incrementAndGet()), this, endpoint, sec, endpointKey(sec), connection, in, request, subprotocol
        );
        sessions.add(session);
        upgradable.upgraded(session::run);
    }

    /**
     * Checks the handshake is one of RFC 6455 this server speaks.
     *
     * @return The {@code Sec-WebSocket-Key}
     */
    private static String validate(JdkHandshakeRequest request, Object httpServletResponse) {
        String version = request.header(HandshakeRequest.SEC_WEBSOCKET_VERSION);
        if (version == null || !WebSocketHandshake.VERSION.equals(version.trim())) {
            if (httpServletResponse instanceof HttpServletResponse response) {
                response.setHeader(HandshakeRequest.SEC_WEBSOCKET_VERSION, WebSocketHandshake.VERSION);
            }
            throw new HttpStatusException(HttpStatus.UPGRADE_REQUIRED, "Unsupported WebSocket version: only 13 is supported");
        }
        String key = request.header(HandshakeRequest.SEC_WEBSOCKET_KEY);
        if (key == null || !WebSocketHandshake.isValidKey(key)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid Sec-WebSocket-Key");
        }
        return key;
    }

    private static String negotiateSubprotocol(ServerEndpointConfig.Configurator configurator,
                                               ServerEndpointConfig sec,
                                               JdkHandshakeRequest request) {
        List<String> requested = WebSocketHandshake.subprotocols(request.getHeaders().get(HandshakeRequest.SEC_WEBSOCKET_PROTOCOL));
        if (requested.isEmpty()) {
            return "";
        }
        String negotiated = configurator.getNegotiatedSubprotocol(sec.getSubprotocols(), requested);
        return negotiated == null ? "" : negotiated;
    }

    private static Endpoint endpoint(ServerEndpointConfig.Configurator configurator, ServerEndpointConfig sec) throws DeploymentException {
        try {
            Object endpoint = configurator.getEndpointInstance(sec.getEndpointClass());
            if (endpoint instanceof Endpoint jakartaEndpoint) {
                return jakartaEndpoint;
            }
            throw new DeploymentException("Not a jakarta.websocket.Endpoint: " + sec.getEndpointClass().getName());
        } catch (InstantiationException e) {
            throw new DeploymentException("Cannot create the endpoint " + sec.getEndpointClass().getName(), e);
        }
    }

    /**
     * The threads of the asynchronous sends and of the timeouts. They are virtual threads, one per
     * task, never the bounded pool of the server: its workers are taken by the readers of the
     * connections, which would leave no thread for a send, and a write blocked on a peer that reads
     * nothing must not hold up the timeouts of the other sessions.
     */
    private ExecutorService workers() {
        synchronized (schedulerLock) {
            if (workers == null) {
                workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("micronaut-jdk-websocket-", 1L).factory());
            }
            return workers;
        }
    }

    /**
     * Runs a task later, for the timeouts of the sessions. The timer thread only hands the task
     * over to {@link #workers()}, so that a task that blocks, e.g. on the write of a close frame,
     * delays no other.
     *
     * @param task        The task
     * @param delayMillis In how long
     * @return Its future, to cancel it
     */
    Future<?> schedule(Runnable task, long delayMillis) {
        ScheduledExecutorService executor;
        synchronized (schedulerLock) {
            if (scheduler == null) {
                ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform()
                    .name("micronaut-jdk-websocket-timer")
                    .daemon(true)
                    .factory());
                created.setRemoveOnCancelPolicy(true);
                scheduler = created;
            }
            executor = scheduler;
        }
        return executor.schedule(() -> dispatch(task), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void dispatch(Runnable task) {
        try {
            workers().execute(task);
        } catch (RejectedExecutionException e) {
            // the container is stopping
            LOG.debug("WebSocket timeout task rejected: {}", e.getMessage());
        }
    }

    /**
     * @param session A session that ended
     */
    void removeSession(JdkWebSocketSession session) {
        sessions.remove(session);
    }

    /**
     * @param endpointKey What identifies the endpoint of a session, see {@link #endpointKey(ServerEndpointConfig)}
     * @return The open sessions of the same endpoint
     */
    Set<Session> openSessions(Object endpointKey) {
        return sessions.stream()
            .filter(JdkWebSocketSession::isOpen)
            .filter(session -> session.getEndpointKey().equals(endpointKey))
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * What identifies the endpoint of a connection: the route the upgrade matched, so that the
     * connections to {@code /chat/a} and {@code /chat/b} of a route {@code /chat/{room}} are of one
     * endpoint, and two routes of one path on different ports are not. Without the context of
     * micronaut-servlet-websocket, the endpoint class and the path.
     *
     * @param sec The configuration of the connection
     * @return The key
     */
    static Object endpointKey(ServerEndpointConfig sec) {
        if (UPGRADE_CONTEXT_PRESENT) {
            Object route = RouteKey.of(sec);
            if (route != null) {
                return route;
            }
        }
        return List.of(sec.getEndpointClass(), sec.getPath());
    }

    /**
     * Closes the open sessions with {@code 1001 Going Away}, and stops the timer.
     */
    @PreDestroy
    void close() {
        // off the thread that stops the server: a session whose write is blocked on a peer that reads
        // nothing cannot send its close, and is dropped once the others had the time to
        List<CompletableFuture<Void>> closes = new ArrayList<>();
        for (JdkWebSocketSession session : sessions) {
            try {
                closes.add(CompletableFuture.runAsync(session::goingAway, workers()));
            } catch (RejectedExecutionException e) {
                session.goingAway();
            }
        }
        try {
            CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new)).get(SHUTDOWN_CLOSE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.debug("WebSocket sessions not closed in time, dropping them: {}", e.getMessage());
        }
        for (JdkWebSocketSession session : sessions) {
            if (!session.isCloseSent()) {
                session.abort();
            }
        }
        synchronized (schedulerLock) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
            if (workers != null) {
                workers.shutdownNow();
                workers = null;
            }
        }
    }

    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException {
        throw notDeployable();
    }

    @Override
    public void addEndpoint(ServerEndpointConfig serverConfig) throws DeploymentException {
        throw notDeployable();
    }

    private static DeploymentException notDeployable() {
        return new DeploymentException("Endpoints are routed by Micronaut on the built-in JDK HTTP server, "
            + "declare them as @ServerWebSocket beans, @ServerEndpoint beans or WebSocket routes");
    }

    @Override
    public long getDefaultAsyncSendTimeout() {
        return asyncSendTimeout;
    }

    @Override
    public void setAsyncSendTimeout(long timeoutmillis) {
        this.asyncSendTimeout = timeoutmillis;
    }

    @Override
    public Session connectToServer(Object annotatedEndpointInstance, URI path) throws DeploymentException {
        throw noClient();
    }

    @Override
    public Session connectToServer(Class<?> annotatedEndpointClass, URI path) throws DeploymentException {
        throw noClient();
    }

    @Override
    public Session connectToServer(Endpoint endpointInstance, ClientEndpointConfig cec, URI path) throws DeploymentException {
        throw noClient();
    }

    @Override
    public Session connectToServer(Class<? extends Endpoint> endpointClass, ClientEndpointConfig cec, URI path) throws DeploymentException {
        throw noClient();
    }

    private static DeploymentException noClient() {
        return new DeploymentException("The built-in JDK HTTP server opens no WebSocket client connections: "
            + "use the Micronaut WebSocket client, or a Jakarta WebSocket client implementation");
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout() {
        return maxSessionIdleTimeout;
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long timeout) {
        this.maxSessionIdleTimeout = timeout;
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize() {
        return maxBinaryMessageBufferSize;
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max) {
        this.maxBinaryMessageBufferSize = max;
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize() {
        return maxTextMessageBufferSize;
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max) {
        this.maxTextMessageBufferSize = max;
    }

    @Override
    public Set<Extension> getInstalledExtensions() {
        // no permessage-deflate: no extension is negotiated
        return Set.of();
    }

    /**
     * Reads the route of a connection from the context micronaut-servlet-websocket gives it, only
     * loaded when that module is present.
     */
    private static final class RouteKey {
        private RouteKey() {
        }

        static @Nullable Object of(ServerEndpointConfig sec) {
            for (Object value : sec.getUserProperties().values()) {
                if (value instanceof WebSocketUpgradeContext context) {
                    return context.routeMatch().getRouteInfo();
                }
            }
            return null;
        }
    }
}
