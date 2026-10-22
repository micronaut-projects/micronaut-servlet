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

import com.sun.net.httpserver.spi.HttpServerProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.servlet.http.server.jdk.JdkHttpServerConfiguration;
import io.micronaut.servlet.http.server.jdk.JdkHttpServerConfiguration.WebSocketUpgrade;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * Whether this server can upgrade a WebSocket handshake.
 *
 * <p>The {@code com.sun.net.httpserver} provider of the JDK cannot: it ends the exchange right
 * after the headers of a {@code 101 Switching Protocols}, and gives the body of a {@code GET} as
 * empty (JDK-8368695). A provider that leaves the streams of the exchange as those of the
 * connection after a {@code 101}, like {@code io.github.robaho:httpserver}, can. Which provider the
 * server is created with is decided by {@link HttpServerProvider#provider()}, by the system
 * property {@code com.sun.net.httpserver.HttpServerProvider} or the first provider on the
 * classpath, so that is the one checked.</p>
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
@Singleton
public final class JdkWebSocketCapability {

    /**
     * The property that decides whether a handshake is upgraded.
     */
    public static final String PROPERTY = JdkHttpServerConfiguration.PREFIX + ".websocket";

    /**
     * The packages of the providers known to keep the connection open after a {@code 101}.
     */
    private static final List<String> UPGRADING_PROVIDERS = List.of("robaho.net.httpserver.");

    private static final String ALTERNATIVES = "Use the Jetty, Tomcat, Undertow or Netty runtime for WebSocket, "
        + "or add io.github.robaho:httpserver to the classpath, whose HttpServer provider supports the upgrade.";

    private final WebSocketUpgrade upgrade;
    private final String provider;

    /**
     * @param configuration The server configuration
     */
    public JdkWebSocketCapability(HttpServerConfiguration configuration) {
        this(configuration instanceof JdkHttpServerConfiguration jdk ? jdk.getWebsocket() : WebSocketUpgrade.AUTO,
            HttpServerProvider.provider().getClass().getName());
    }

    /**
     * @param upgrade  Whether a handshake is upgraded
     * @param provider The class name of the provider
     */
    JdkWebSocketCapability(WebSocketUpgrade upgrade, String provider) {
        this.upgrade = upgrade;
        this.provider = provider;
    }

    /**
     * @return Whether a WebSocket handshake is upgraded
     */
    public boolean isSupported() {
        return switch (upgrade) {
            case ENABLED -> true;
            case DISABLED -> false;
            case AUTO -> isUpgradingProvider(provider);
        };
    }

    /**
     * @return The class name of the {@code com.sun.net.httpserver} provider
     */
    public String getProvider() {
        return provider;
    }

    /**
     * @return Why a handshake is not upgraded, and what to do about it
     */
    public String unsupportedMessage() {
        if (upgrade == WebSocketUpgrade.DISABLED) {
            return "WebSocket is disabled on the built-in JDK HTTP server with " + PROPERTY + "=disabled.";
        }
        return "The built-in JDK HTTP server cannot upgrade to WebSocket: its HttpServer provider ["
            + provider + "] does not support switching protocols. " + ALTERNATIVES;
    }

    /**
     * @param provider The class name of a provider
     * @return Whether it is known to keep the connection open after a {@code 101}
     */
    static boolean isUpgradingProvider(String provider) {
        return UPGRADING_PROVIDERS.stream().anyMatch(provider::startsWith);
    }
}
