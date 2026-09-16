package io.micronaut.servlet.websocket;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Extension;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A container that opens a {@link TestSession} on the spot: the configurator sees the handshake
 * headers, the endpoint is opened, and the session returned, as a real client container does.
 */
final class TestContainer implements WebSocketContainer {

    final TestSession session = new TestSession();
    final Map<String, List<String>> handshakeHeaders = new HashMap<>(Map.of("X-Container", List.of("test")));

    Endpoint endpoint;
    ClientEndpointConfig config;
    URI uri;
    Class<?> scannedClass;
    Object scannedInstance;
    long asyncSendTimeout;
    long maxSessionIdleTimeout;
    int maxBinaryMessageBufferSize;
    int maxTextMessageBufferSize;

    @Override
    public Session connectToServer(Endpoint endpointInstance, ClientEndpointConfig cec, URI path) {
        this.endpoint = endpointInstance;
        this.config = cec;
        this.uri = path;
        cec.getConfigurator().beforeRequest(handshakeHeaders);
        endpointInstance.onOpen(session, cec);
        return session;
    }

    @Override
    public Session connectToServer(Class<?> annotatedEndpointClass, URI path) {
        this.scannedClass = annotatedEndpointClass;
        this.uri = path;
        return session;
    }

    @Override
    public Session connectToServer(Object annotatedEndpointInstance, URI path) {
        this.scannedInstance = annotatedEndpointInstance;
        this.uri = path;
        return session;
    }

    @Override
    public Session connectToServer(Class<? extends Endpoint> endpointClass, ClientEndpointConfig cec, URI path) {
        throw new UnsupportedOperationException("the Micronaut container instantiates the endpoint itself");
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
        return Set.of();
    }
}
