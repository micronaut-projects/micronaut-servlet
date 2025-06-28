/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.servlet.jetty;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.ssl.ServerSslConfiguration;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import io.micronaut.runtime.context.scope.refresh.RefreshEventListener;
import jakarta.inject.Singleton;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Listener implementation which reloads ssl context when ssl properties have been changed.
 */
@Singleton
public class JettySslContextReload implements RefreshEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(JettySslContextReload.class);

    private final JettyFactory jettyFactory;
    private final Server server;

    /**
     * Constructor.
     *
     * @param jettyFactory the jetty factory
     * @param server       the jetty server
     */
    public JettySslContextReload(JettyFactory jettyFactory, Server server) {
        this.jettyFactory = jettyFactory;
        this.server = server;
    }

    @Override
    public @NonNull Set<String> getObservedConfigurationPrefixes() {
        return CollectionUtils.setOf(
            SslConfiguration.PREFIX,
            ServerSslConfiguration.PREFIX
        );
    }

    @Override
    public void onApplicationEvent(RefreshEvent event) {
        Connector[] connectors = server.getConnectors();

        Optional<ConnectionFactory> connectionFactoryOpt = Arrays.stream(connectors)
            .flatMap(connector -> connector.getConnectionFactories().stream())
            .filter(connectionFactory -> connectionFactory instanceof SslConnectionFactory)
            .findFirst();

        if (connectionFactoryOpt.isPresent()) {
            SslConnectionFactory sslConnectionFactory = (SslConnectionFactory) connectionFactoryOpt.get();
            SslContextFactory.Server sslContextFactory = sslConnectionFactory.getSslContextFactory();
            try {
                jettyFactory.updateSslContextFactory(sslContextFactory, ResourceFactory.of(server));
                sslContextFactory.reload(factory -> { });
            } catch (Exception e) {
                LOG.error("Failed to reload ssl context", e);
            }
        }
    }
}
