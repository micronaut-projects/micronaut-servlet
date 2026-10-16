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
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.HttpServerConfiguration;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    private static final int DEFAULT_MAX_MESSAGE_SIZE = 65536;
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
    private @Nullable ExecutorService fallbackExecutor;
    private volatile long asyncSendTimeout;
    private volatile long maxSessionIdleTimeout;
    private volatile int maxBinaryMessageBufferSize = DEFAULT_MAX_MESSAGE_SIZE;
    private volatile int maxTextMessageBufferSize = DEFAULT_MAX_MESSAGE_SIZE;

    /**
     * @param capability    Whether a handshake can be upgraded
     * @param configuration The server configuration, for the default idle timeout
     */
    JdkServerContainer(JdkWebSocketCapability capability, HttpServerConfiguration configuration) {
        this.capability = capability;
        this.maxSessionIdleTimeout = configuration.getIdleTimeout().toMillis();
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
        WebSocketConnection connection = new WebSocketConnection(out, executor(exchange), exchange::close);
        JdkWebSocketSession session = new JdkWebSocketSession(
            Long.toHexString(ids.incrementAndGet()), this, endpoint, sec, connection, in, request, subprotocol
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

    private Executor executor(HttpExchange exchange) {
        HttpServer server = exchange.getHttpContext().getServer();
        Executor executor = server != null ? server.getExecutor() : null;
        if (executor != null) {
            return executor;
        }
        synchronized (schedulerLock) {
            if (fallbackExecutor == null) {
                fallbackExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("micronaut-jdk-websocket-", 1L).factory());
            }
            return fallbackExecutor;
        }
    }

    /**
     * Runs a task later, for the timeouts of the sessions.
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
        return executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * @param session A session that ended
     */
    void removeSession(JdkWebSocketSession session) {
        sessions.remove(session);
    }

    /**
     * @param config The configuration of a session
     * @return The open sessions of the same endpoint
     */
    Set<Session> openSessions(ServerEndpointConfig config) {
        return sessions.stream()
            .filter(JdkWebSocketSession::isOpen)
            .filter(session -> session.getConfig().getEndpointClass() == config.getEndpointClass()
                && session.getConfig().getPath().equals(config.getPath()))
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Closes the open sessions with {@code 1001 Going Away}, and stops the timer.
     */
    @PreDestroy
    void close() {
        for (JdkWebSocketSession session : sessions) {
            session.goingAway();
        }
        synchronized (schedulerLock) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
            if (fallbackExecutor != null) {
                fallbackExecutor.shutdownNow();
                fallbackExecutor = null;
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
}
