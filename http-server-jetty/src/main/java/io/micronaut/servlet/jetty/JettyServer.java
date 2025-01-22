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
package io.micronaut.servlet.jetty;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.servlet.engine.server.AbstractServletServer;
import io.micronaut.web.router.Router;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * An implementation of the {@link io.micronaut.runtime.server.EmbeddedServer} interface for Jetty.
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
public class JettyServer extends AbstractServletServer<Server> {

    private final Router router;

    /**
     * Default constructor.
     *
     * @param applicationContext       The application context
     * @param applicationConfiguration The application configuration
     * @param server                   The jetty server
     */
    @Deprecated(forRemoval = true, since = "5.0")
    public JettyServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            Server server) {
        super(applicationContext, applicationConfiguration, server);
        this.router = applicationContext.getBean(Router.class);
    }

    /**
     * Default constructor.
     *
     * @param applicationContext       The application context
     * @param applicationConfiguration The application configuration
     * @param server                   The jetty server
     * @param router                   The router
     * @param jettyConfiguration       The jetty configuration
     * @param connectors               Additional connector configuration
     */
    @Inject
    public JettyServer(
        ApplicationContext applicationContext,
        ApplicationConfiguration applicationConfiguration,
        Server server,
        Router router,
        JettyConfiguration jettyConfiguration,
        List<JettyConfiguration.ConnectorConfiguration> connectors) {
        super(applicationContext, applicationConfiguration, server);
        this.router = router;
        applyConnectorConfiguration(jettyConfiguration, server, connectors);
    }

    @Override
    protected void startServer() throws Exception {
        Server server = getServer();
        server.start();
    }

    @Override
    protected void stopServer() throws Exception {
        getServer().stop();
    }

    @Override
    public int getPort() {
        Server server = getServer();
        return server.getURI().getPort();
    }

    @Override
    public String getHost() {
        return getServer().getURI().getHost();
    }

    @Override
    public String getScheme() {
        return getServer().getURI().getScheme();
    }

    @Override
    public URL getURL() {
        try {
            return getServer().getURI().toURL();
        } catch (MalformedURLException e) {
            throw new HttpServerException(e.getMessage(), e);
        }
    }

    @Override
    public URI getURI() {
        return getServer().getURI();
    }

    @Override
    public boolean isRunning() {
        return getServer().isRunning();
    }

    private void applyConnectorConfiguration(JettyConfiguration jettyConfiguration, Server server, List<JettyConfiguration.ConnectorConfiguration> configuredConnectors) {
        // first connector
        Connector[] serverConnectors = server.getConnectors();
        ServerConnector serverConnector = (ServerConnector) serverConnectors[0];
        List<JettyConfiguration.ConnectorConfiguration> connectorConfigurations = new ArrayList<>(configuredConnectors);
        applyAdditionalPorts(jettyConfiguration, server, serverConnector, connectorConfigurations);

        for (Connector connector : serverConnectors) {
            if (connector instanceof ServerConnector sc) {
                connectorConfigurations.stream()
                    .filter(cc -> cc.getPort() == sc.getPort())
                    .findFirst().ifPresent(connectorConfiguration -> configureExistingConnector(sc, connectorConfigurations, connectorConfiguration));
            }
        }
        for (JettyConfiguration.ConnectorConfiguration connector : connectorConfigurations) {
            server.addConnector(connector);
        }
    }

    private static void configureExistingConnector(ServerConnector sc, List<JettyConfiguration.ConnectorConfiguration> connectorConfigurations, JettyConfiguration.ConnectorConfiguration connectorConfiguration) {
        connectorConfigurations.remove(connectorConfiguration);
        sc.setHost(connectorConfiguration.getHost());
        sc.setAccepting(connectorConfiguration.isAccepting());
        sc.setInheritChannel(connectorConfiguration.isInheritChannel());
        sc.setAcceptedReceiveBufferSize(connectorConfiguration.getAcceptedReceiveBufferSize());
        sc.setAcceptedTcpNoDelay(connectorConfiguration.getAcceptedTcpNoDelay());
        sc.setAcceptedSendBufferSize(connectorConfiguration.getAcceptedSendBufferSize());
        sc.setAcceptQueueSize(connectorConfiguration.getAcceptQueueSize());
        sc.setReuseAddress(connectorConfiguration.getReuseAddress());
        sc.setReusePort(connectorConfiguration.isReusePort());
        sc.setAcceptorPriorityDelta(connectorConfiguration.getAcceptorPriorityDelta());
        sc.setInheritChannel(connectorConfiguration.isInheritChannel());
        sc.setAccepting(connectorConfiguration.isAccepting());
        sc.setIdleTimeout(connectorConfiguration.getIdleTimeout());
        sc.setShutdownIdleTimeout(connectorConfiguration.getShutdownIdleTimeout());
        sc.setDefaultProtocol(connectorConfiguration.getDefaultProtocol());
    }

    private void applyAdditionalPorts(JettyConfiguration jettyConfiguration, Server server, ServerConnector serverConnector, List<JettyConfiguration.ConnectorConfiguration> connectors) {
        Set<Integer> exposedPorts = router.getExposedPorts();
        if (CollectionUtils.isNotEmpty(exposedPorts)) {
            for (Integer exposedPort : exposedPorts) {
                if (!exposedPort.equals(serverConnector.getLocalPort())) {
                    JettyConfiguration.ConnectorConfiguration connectorConfiguration = connectors.stream().filter(c -> c.getPort() == exposedPort)
                        .findFirst().orElse(null);
                    Collection<ConnectionFactory> connectionFactories = serverConnector.getConnectionFactories();
                    if (connectorConfiguration != null) {
                        connectors.remove(connectorConfiguration);
                        handleConnectionConfiguration(jettyConfiguration, server, connectorConfiguration, connectionFactories, serverConnector);
                    } else {
                        ServerConnector connector = new ServerConnector(
                            server,
                            connectionFactories.toArray(ConnectionFactory[]::new)
                        );
                        connector.setPort(exposedPort);
                        connector.setHost(serverConnector.getHost());
                        server.addConnector(connector);
                    }
                }
            }
        }
    }

    private static void handleConnectionConfiguration(JettyConfiguration jettyConfiguration, Server server, JettyConfiguration.ConnectorConfiguration connectorConfiguration, Collection<ConnectionFactory> connectionFactories, ServerConnector serverConnector) {
        String defaultProtocol = connectorConfiguration.getDefaultProtocol();
        Collection<ConnectionFactory> resolvedFactories = new ArrayList<>(connectionFactories);
        if (!connectorConfiguration.isSslEnabled()) {
            // remove SSL if it is disabled
            resolvedFactories.removeIf(cf -> cf.getProtocol().equalsIgnoreCase("SSL"));
        }
        if (defaultProtocol != null && defaultProtocol.equalsIgnoreCase(HttpVersion.HTTP_1_1.name())) {
            if (resolvedFactories.stream()
                .noneMatch(cf -> cf.getProtocol().equalsIgnoreCase(HttpVersion.HTTP_1_1.name()))) {
                resolvedFactories.add(new HttpConnectionFactory(
                    jettyConfiguration.getHttpConfiguration()
                ));
            }
        }
        if (connectorConfiguration.getHost() == null) {
            connectorConfiguration.setHost(serverConnector.getHost());
        }

        connectorConfiguration.setConnectionFactories(resolvedFactories);
        server.addConnector(connectorConfiguration);
    }
}
