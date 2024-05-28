/*
 * Copyright 2017-2024 original authors
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

import static io.micronaut.core.util.StringUtils.isEmpty;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.env.Environment;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.ssl.ClientAuthentication;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.LoomSupport;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import io.micronaut.servlet.engine.initializer.MicronautServletInitializer;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import io.micronaut.servlet.engine.server.ServletStaticResourceConfiguration;
import io.micronaut.web.router.Router;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * Factory for the Jetty server.
 *
 * @author graemerocher
 * @since 1.0
 */
@Factory
public class JettyFactory extends ServletServerFactory {

    public static final String RESOURCE_BASE = "resourceBase";

    private final JettyConfiguration jettyConfiguration;
    private final Router router;

    /**
     * Default constructor.
     *
     * @param resourceResolver             The resource resolver
     * @param serverConfiguration          The server config
     * @param sslConfiguration             The SSL config
     * @param applicationContext           The app context
     * @param staticResourceConfigurations The static resource configs
     */
    public JettyFactory(
        ResourceResolver resourceResolver,
        JettyConfiguration serverConfiguration,
        SslConfiguration sslConfiguration,
        ApplicationContext applicationContext,
        List<ServletStaticResourceConfiguration> staticResourceConfigurations) {
        super(
            resourceResolver,
            serverConfiguration,
            sslConfiguration,
            applicationContext,
            staticResourceConfigurations
        );
        this.jettyConfiguration = serverConfiguration;
        this.router = applicationContext.findBean(Router.class).orElse(null);
    }

    /**
     * Builds the Jetty server bean.
     *
     * @param applicationContext    This application context
     * @param configuration         The servlet configuration
     * @param jettySslConfiguration The Jetty SSL config
     * @return The Jetty server bean
     */
    protected Server jettyServer(
        ApplicationContext applicationContext,
        MicronautServletConfiguration configuration,
        JettyConfiguration.JettySslConfiguration jettySslConfiguration
    ) {
        return jettyServer(
            applicationContext,
            configuration,
            jettySslConfiguration,
            applicationContext.getBean(MicronautServletInitializer.class)
        );
    }

    /**
     * Builds the Jetty server bean.
     *
     * @param applicationContext          This application context
     * @param configuration               The servlet configuration
     * @param jettySslConfiguration       The Jetty SSL config
     * @param micronautServletInitializer The micronaut servlet initializer
     * @return The Jetty server bean
     */
    @Singleton
    @Primary
    protected Server jettyServer(
        ApplicationContext applicationContext,
        MicronautServletConfiguration configuration,
        JettyConfiguration.JettySslConfiguration jettySslConfiguration,
        MicronautServletInitializer micronautServletInitializer
    ) {
        final String host = getConfiguredHost();
        final Integer port = getConfiguredPort();
        String contextPath = getContextPath();

        Server server = newServer(applicationContext, configuration);

        final ServletContextHandler contextHandler = newJettyContext(server, contextPath);
        configureServletInitializer(server, contextHandler, micronautServletInitializer);

        final SslConfiguration sslConfiguration = getSslConfiguration();
        ServerConnector https = null;
        if (sslConfiguration.isEnabled()) {
            https = newHttpsConnector(server, sslConfiguration, jettySslConfiguration);

        }
        final ServerConnector http = newHttpConnector(server, host, port);
        configureConnectors(server, http, https);

        return server;
    }

    /**
     * Create the HTTP connector.
     * @param server The server
     * @param host The host
     * @param port The port
     * @return The server connector.
     */
    protected @NonNull ServerConnector newHttpConnector(@NonNull Server server, @NonNull String host, @NonNull Integer port) {
        HttpConfiguration httpConfig = jettyConfiguration.getHttpConfiguration();
        HttpConnectionFactory http11 = new HttpConnectionFactory(httpConfig);
        HttpServerConfiguration serverConfiguration = getServerConfiguration();
        final ServerConnector http;
        if (serverConfiguration.getHttpVersion() == io.micronaut.http.HttpVersion.HTTP_2_0) {
            HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);
            http = new ServerConnector(server, http11, h2c);
        } else {
            http = new ServerConnector(server, http11);
        }

        http.setPort(port);
        http.setHost(host);
        return http;
    }

    /**
     * Create the HTTPS connector.
     *
     * @param server                The server
     * @param sslConfiguration      The SSL configuration
     * @param jettySslConfiguration The Jetty SSL configuration
     * @return The server connector
     */
    protected @NonNull ServerConnector newHttpsConnector(
        @NonNull Server server,
        @NonNull SslConfiguration sslConfiguration,
        @NonNull JettyConfiguration.JettySslConfiguration jettySslConfiguration) {
        ServerConnector https;
        final HttpConfiguration httpConfig = jettyConfiguration.getHttpConfiguration();
        int securePort = sslConfiguration.getPort();
        if (securePort == SslConfiguration.DEFAULT_PORT && getEnvironment().getActiveNames().contains(Environment.TEST)) {
            securePort = 0; // random port
        }
        httpConfig.setSecurePort(securePort);

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

        ClientAuthentication clientAuth = sslConfiguration.getClientAuthentication().orElse(ClientAuthentication.NEED);
        switch (clientAuth) {
            case WANT:
                sslContextFactory.setWantClientAuth(true);
                break;
            case NEED:
            default:
                sslContextFactory.setNeedClientAuth(true);
        }

        sslConfiguration.getProtocol().ifPresent(sslContextFactory::setProtocol);
        sslConfiguration.getProtocols().ifPresent(sslContextFactory::setIncludeProtocols);
        sslConfiguration.getCiphers().ifPresent(sslConfiguration::setCiphers);
        final SslConfiguration.KeyStoreConfiguration keyStoreConfig = sslConfiguration.getKeyStore();
        keyStoreConfig.getPassword().ifPresent(sslContextFactory::setKeyStorePassword);
        keyStoreConfig.getPath().ifPresent(path -> {
            if (path.startsWith(ServletStaticResourceConfiguration.CLASSPATH_PREFIX)) {
                String cp = path.substring(ServletStaticResourceConfiguration.CLASSPATH_PREFIX.length());
                sslContextFactory.setKeyStorePath(Resource.newClassPathResource(cp).getURI().toString());
            } else {
                sslContextFactory.setKeyStorePath(path);
            }
        });
        keyStoreConfig.getProvider().ifPresent(sslContextFactory::setKeyStoreProvider);
        keyStoreConfig.getType().ifPresent(sslContextFactory::setKeyStoreType);
        SslConfiguration.TrustStoreConfiguration trustStore = sslConfiguration.getTrustStore();
        trustStore.getPassword().ifPresent(sslContextFactory::setTrustStorePassword);
        trustStore.getType().ifPresent(sslContextFactory::setTrustStoreType);
        trustStore.getPath().ifPresent(path -> {
            if (path.startsWith(ServletStaticResourceConfiguration.CLASSPATH_PREFIX)) {
                String cp = path.substring(ServletStaticResourceConfiguration.CLASSPATH_PREFIX.length());
                sslContextFactory.setTrustStorePath(Resource.newClassPathResource(cp).getURI().toString());
            } else {
                sslContextFactory.setTrustStorePath(path);
            }
        });
        trustStore.getProvider().ifPresent(sslContextFactory::setTrustStoreProvider);

        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(jettySslConfiguration);

        // The ConnectionFactory for HTTP/1.1.
        HttpConnectionFactory http11 = new HttpConnectionFactory(httpsConfig);

        if (getServerConfiguration().getHttpVersion() == io.micronaut.http.HttpVersion.HTTP_2_0) {
            // The ConnectionFactory for HTTP/2.
            HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfig);
            // The ALPN ConnectionFactory.
            ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
            // The default protocol to use in case there is no negotiation.
            alpn.setDefaultProtocol(http11.getProtocol());
            // The ConnectionFactory for TLS.
            SslConnectionFactory tls = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
            // The ServerConnector instance.
            https = new ServerConnector(server, tls, alpn, h2, http11);
        } else {
            SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString());
            https = new ServerConnector(server,
                sslConnectionFactory,
                http11
            );
        }

        https.setPort(securePort);
        return https;
    }

    /**
     * Configures the servlet initializer.
     *
     * @param server                      The server
     * @param contextHandler              The context handler
     * @param micronautServletInitializer The initializer
     */
    protected void configureServletInitializer(Server server, ServletContextHandler contextHandler, MicronautServletInitializer micronautServletInitializer) {
        contextHandler.addServletContainerInitializer(micronautServletInitializer);

        List<ContextHandler> resourceHandlers = Stream.concat(
            getStaticResourceConfigurations().stream().map(this::toHandler),
            Stream.of(contextHandler)
        ).toList();

        HandlerList handlerList = new HandlerList(resourceHandlers.toArray(new ContextHandler[0]));
        server.setHandler(handlerList);
    }

    /**
     * Create the Jetty context.
     *
     * @param server      The server
     * @param contextPath The context path
     * @return The handler
     */
    protected @NonNull ServletContextHandler newJettyContext(@NonNull Server server, @NonNull String contextPath) {
        return new ServletContextHandler(server, contextPath, false, false);
    }

    /**
     * Configures the server connectors.
     *
     * @param server        The server
     * @param http          The HTTP connector
     * @param https         The HTTPS connector if configured.
     */
    protected void configureConnectors(@NonNull Server server, @NonNull ServerConnector http, @Nullable ServerConnector https) {
        HttpServerConfiguration serverConfiguration = getServerConfiguration();
        if (https != null) {
            server.addConnector(https); // must be first
            if (serverConfiguration.isDualProtocol()) {
                server.addConnector(http);
            }
            applyAdditionalPorts(server, https);
        } else {
            server.addConnector(http);
            applyAdditionalPorts(server, http);
        }
    }

    private void applyAdditionalPorts(Server server, ServerConnector serverConnector) {
        if (router != null) {
            Set<Integer> exposedPorts = router.getExposedPorts();
            if (CollectionUtils.isNotEmpty(exposedPorts)) {
                for (Integer exposedPort : exposedPorts) {
                    if (!exposedPort.equals(serverConnector.getLocalPort())) {
                        ServerConnector connector = new ServerConnector(
                            server,
                            serverConnector.getConnectionFactories().toArray(ConnectionFactory[]::new)
                        );
                        connector.setPort(exposedPort);
                        connector.setHost(getConfiguredHost());
                        server.addConnector(connector);
                    }
                }
            }
        }
    }

    /**
     * Create a new server instance.
     *
     * @param applicationContext The application context
     * @param configuration      The configuration
     * @return The server
     */
    protected @NonNull Server newServer(@NonNull ApplicationContext applicationContext, @NonNull MicronautServletConfiguration configuration) {
        Server server;
        if (configuration.isEnableVirtualThreads() && LoomSupport.isSupported()) {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setVirtualThreadsExecutor(
                applicationContext.getBean(ExecutorService.class, Qualifiers.byName(TaskExecutors.BLOCKING))
            );
            server = new Server(threadPool);
        } else {
            server = new Server();
        }
        return server;
    }

    /**
     * For each static resource configuration, create a {@link ContextHandler} that serves the static resources.
     *
     * @param config The static resource configuration
     * @return the context handler
     */
    private ContextHandler toHandler(ServletStaticResourceConfiguration config) {
        Resource[] resourceArray = config.getPaths().stream()
            .map(path -> {
                if (path.startsWith(ServletStaticResourceConfiguration.CLASSPATH_PREFIX)) {
                    String cp = path.substring(ServletStaticResourceConfiguration.CLASSPATH_PREFIX.length());
                    return Resource.newClassPathResource(cp);
                } else {
                    try {
                        return Resource.newResource(path);
                    } catch (IOException e) {
                        throw new ConfigurationException("Static resource path doesn't exist: " + path, e);
                    }
                }
            }).toArray(Resource[]::new);

        String path = config.getMapping();
        if (path.endsWith("/**")) {
            path = path.substring(0, path.length() - 3);
        }

        final String mapping = path;

        ResourceCollection mappedResourceCollection = new ResourceCollection(resourceArray) {
            @Override
            public Resource addPath(String path) throws IOException {
                return super.addPath(path.substring(mapping.length()));
            }
        };

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(mappedResourceCollection);
        resourceHandler.setDirAllowed(false);
        resourceHandler.setDirectoriesListed(false);
        if (!isEmpty(config.getCacheControl())) {
            resourceHandler.setCacheControl(config.getCacheControl());
        }

        ContextHandler contextHandler = new ContextHandler(path);
        contextHandler.setContextPath("/");
        contextHandler.setHandler(resourceHandler);
        contextHandler.setDisplayName("Static Resources " + mapping);

        return contextHandler;
    }
}
