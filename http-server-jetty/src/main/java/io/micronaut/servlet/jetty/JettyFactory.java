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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.env.Environment;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.http.ssl.ClientAuthentication;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import io.micronaut.servlet.engine.server.ServletStaticResourceConfiguration;
import jakarta.inject.Singleton;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import static io.micronaut.core.util.StringUtils.isEmpty;

/**
 * Factory for the Jetty server.
 *
 * @author graemerocher
 * @since 1.0
 */
@Factory
public class JettyFactory extends ServletServerFactory {

    public static final String RESOURCE_BASE = "resourceBase";

    private static final Logger LOG = LoggerFactory.getLogger(JettyFactory.class);

    private final JettyConfiguration jettyConfiguration;

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
    }

    /**
     * Builds the Jetty server bean.
     *
     * @param applicationContext    This application context
     * @param configuration         The servlet configuration
     * @param jettySslConfiguration The Jetty SSL config
     * @return The Jetty server bean
     */
    @Singleton
    @Primary
    protected Server jettyServer(
            ApplicationContext applicationContext,
            MicronautServletConfiguration configuration,
            JettyConfiguration.JettySslConfiguration jettySslConfiguration
    ) {
        final String host = getConfiguredHost();
        final Integer port = getConfiguredPort();
        String contextPath = getContextPath();

        Server server = new Server();

        final ServletContextHandler contextHandler = new ServletContextHandler(server, contextPath, false, false);
        final ServletHolder servletHolder = new ServletHolder(new DefaultMicronautServlet(applicationContext));
        contextHandler.addServlet(servletHolder, configuration.getMapping());

        Boolean isAsync = applicationContext.getEnvironment()
                .getProperty("micronaut.server.testing.async", Boolean.class, true);
        if (Boolean.FALSE.equals(isAsync)) {
            LOG.warn("Async support disabled for testing purposes.");
        }
        servletHolder.setAsyncSupported(isAsync);

        configuration.getMultipartConfigElement().ifPresent(multipartConfiguration ->
                servletHolder.getRegistration().setMultipartConfig(multipartConfiguration)
        );

        List<ContextHandler> resourceHandlers = Stream.concat(
                getStaticResourceConfigurations().stream().map(this::toHandler),
                Stream.of(contextHandler)
        ).toList();

        HandlerList handlerList = new HandlerList(resourceHandlers.toArray(new ContextHandler[0]));
        server.setHandler(handlerList);

        final SslConfiguration sslConfiguration = getSslConfiguration();
        if (sslConfiguration.isEnabled()) {
            final HttpConfiguration httpConfig = jettyConfiguration.getHttpConfiguration();
            int securePort = sslConfiguration.getPort();
            if (securePort == SslConfiguration.DEFAULT_PORT && getEnvironment().getActiveNames().contains(Environment.TEST)) {
                securePort = SocketUtils.findAvailableTcpPort();
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
            ServerConnector https = new ServerConnector(server,
                    new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                    new HttpConnectionFactory(httpsConfig)
            );
            https.setPort(securePort);
            server.addConnector(https);

        }
        final ServerConnector http = new ServerConnector(server, new HttpConnectionFactory(jettyConfiguration.getHttpConfiguration()));
        http.setPort(port);
        http.setHost(host);
        server.addConnector(http);

        return server;
    }

    /**
     * For each static resource configuration, create a {@link ContextHandler} that serves the static resources.
     *
     * @param config
     * @return
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
