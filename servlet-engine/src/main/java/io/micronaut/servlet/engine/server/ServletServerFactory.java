/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.servlet.engine.server;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.http.HttpVersion;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.http.server.exceptions.ServerStartupException;
import io.micronaut.http.ssl.SslBuilder;
import io.micronaut.http.ssl.SslConfiguration;

import javax.net.ssl.*;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Parent factory class for servlet-based servers.
 *
 * @author graemerocher
 * @since 1.0.0
 */
public abstract class ServletServerFactory extends SslBuilder<SSLContext> {
    private final HttpServerConfiguration serverConfiguration;
    private final SslConfiguration sslConfiguration;
    private final ApplicationContext applicationContext;
    private final List<ServletStaticResourceConfiguration> staticResourceConfigurations;

    /**
     * Default constructor.
     *
     * @param resourceResolver             The resource resolver.
     * @param serverConfiguration          The server configuration
     * @param sslConfiguration             The SSL configuration
     * @param applicationContext           The app context
     * @param staticResourceConfigurations The static resource definitions
     */
    protected ServletServerFactory(ResourceResolver resourceResolver,
                                   HttpServerConfiguration serverConfiguration,
                                   SslConfiguration sslConfiguration,
                                   ApplicationContext applicationContext,
                                   List<ServletStaticResourceConfiguration> staticResourceConfigurations) {
        super(resourceResolver);
        this.serverConfiguration = serverConfiguration;
        this.sslConfiguration = sslConfiguration;
        this.applicationContext = applicationContext;
        this.staticResourceConfigurations = staticResourceConfigurations.stream()
                .filter(ServletStaticResourceConfiguration::isEnabled)
                .peek(config -> {
                    List<String> paths = config.getPaths();
                    for (String path : paths) {
                        if (ServletStaticResourceConfiguration.CLASSPATH_PREFIX.equals(path)) {
                            throw new ConfigurationException("A path value of [" + ServletStaticResourceConfiguration.CLASSPATH_PREFIX + "] will allow access to class files!");
                        }
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * @return The static resource configurations.
     */
    public List<ServletStaticResourceConfiguration> getStaticResourceConfigurations() {
        return staticResourceConfigurations;
    }

    /**
     * @return The environment
     */
    public Environment getEnvironment() {
        return applicationContext.getEnvironment();
    }

    /**
     * @return The app context
     */
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    /**
     * @return The context path.
     */
    protected String getContextPath() {
        final String contextPath = serverConfiguration.getContextPath();
        return contextPath != null ? contextPath : "/";
    }

    /**
     * @return The server config
     */
    public HttpServerConfiguration getServerConfiguration() {
        return serverConfiguration;
    }

    /**
     * @return The SSL config
     */
    public SslConfiguration getSslConfiguration() {
        return sslConfiguration;
    }

    @Override
    public Optional<SSLContext> build(SslConfiguration ssl) {
        return build(ssl, HttpVersion.HTTP_1_1);
    }

    //TODO support HTTP/2
    @Override
    public Optional<SSLContext> build(SslConfiguration ssl, io.micronaut.http.HttpVersion httpVersion) {
        if (sslConfiguration.isEnabled()) {
            final String protocol = sslConfiguration
                    .getProtocol().orElseThrow(() -> new ServerStartupException("No SSL protocol specified"));

            try {

                final SSLContext sslContext = SSLContext.getInstance(protocol);
                final KeyManagerFactory keyManagerFactory = getKeyManagerFactory(ssl);
                final KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();
                final TrustManagerFactory trustManagerFactory = getTrustManagerFactory(ssl);
                final TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();

                sslContext.init(
                        keyManagers,
                        trustManagers,
                        new SecureRandom()
                );
                return Optional.of(sslContext);
            } catch (Throwable e) {
                throw new HttpServerException("HTTPS configuration error: " + e.getMessage(), e);
            }
        }
        return Optional.empty();
    }

    /**
     * @return The configured host.
     */
    protected String getConfiguredHost() {
        return serverConfiguration
                .getHost()
                .orElseGet(() -> Optional.ofNullable(System.getenv("HOST")).orElse(SocketUtils.LOCALHOST));
    }

    /**
     * @return The configured port.
     */
    protected Integer getConfiguredPort() {
        return serverConfiguration.getPort().map(p ->
                p == -1 ? SocketUtils.findAvailableTcpPort() : p
        ).orElseGet(() -> {
            if (getEnvironment().getActiveNames().contains(Environment.TEST)) {
                return SocketUtils.findAvailableTcpPort();
            } else {
                return 8080;
            }
        });
    }
}
