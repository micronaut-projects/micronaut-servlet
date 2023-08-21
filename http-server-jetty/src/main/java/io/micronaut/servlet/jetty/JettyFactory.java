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
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.env.Environment;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.ssl.ClientAuthentication;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import io.micronaut.servlet.engine.server.ServletStaticResourceConfiguration;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
     * @param applicationContext This application context
     * @param configuration      The servlet configuration
     * @param jettySslConfiguration The Jetty SSL config
     * @return The Jetty server bean
     */
    @Singleton
    @Primary
    protected Server jettyServer(ApplicationContext applicationContext, MicronautServletConfiguration configuration, JettyConfiguration.JettySslConfiguration jettySslConfiguration) {
        final String host = getConfiguredHost();
        final Integer port = getConfiguredPort();
        Server server = new Server();
        String contextPath = getContextPath();

        List<ServletStaticResourceConfiguration> src = getStaticResourceConfigurations();
        ResourceCollection resourceCollection;

        if (CollectionUtils.isNotEmpty(src)) {
            List<String> mappings = src.stream().map(ServletStaticResourceConfiguration::getMapping)
                    .map(path -> {
                        if (path.endsWith("/**")) {
                            return path.substring(0, path.length() - 3);
                        }
                        return path;
                    })
                    .collect(Collectors.toList());
            resourceCollection = new ResourceCollection(src.stream()
                    .flatMap((Function<ServletStaticResourceConfiguration, Stream<Resource>>) config -> {
                        List<String> paths = config.getPaths();
                        return paths.stream().map(path -> {
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
                        });
                    }).toArray(Resource[]::new)) {
                @Override
                public Resource addPath(String path) throws IOException {
                    for (String mapping : mappings) {
                        if (path.startsWith(mapping)) {
                            path = path.substring(mapping.length());
                        }
                    }
                    return super.addPath(path);
                }
            };
        } else {
            resourceCollection = null;
        }

        final ServletContextHandler contextHandler = new ServletContextHandler(
                server,
                contextPath,
                false,
                false
        ) {
            @Override
            public Resource newResource(String urlOrPath) throws IOException {
                if (resourceCollection != null && RESOURCE_BASE.endsWith(urlOrPath)) {
                    return resourceCollection;
                }
                return super.newResource(urlOrPath);
            }
        };
        final ServletHolder servletHolder = new ServletHolder(new DefaultMicronautServlet(applicationContext));
        contextHandler.addServlet(
                servletHolder,
                configuration.getMapping()
        );

        Boolean isAsync = applicationContext.getEnvironment().getProperty("micronaut.server.testing.async", Boolean.class, true);
        if (Boolean.FALSE.equals(isAsync)) {
            LOG.warn("Async support disabled for testing purposes.");
        }
        servletHolder.setAsyncSupported(isAsync);

        configuration.getMultipartConfigElement().ifPresent(multipartConfiguration ->
                servletHolder.getRegistration().setMultipartConfig(multipartConfiguration)
        );

        if (CollectionUtils.isNotEmpty(src)) {


            List<String> mappings = src.stream()
                    .map(config -> {
                        String mapping = config.getMapping();
                        if (mapping.endsWith("**")) {
                            return mapping.substring(0, mapping.length() - 1);
                        } else if (!mapping.endsWith("/*")) {
                            return mapping + "/*";
                        }
                        return mapping;
                    })
                    .collect(Collectors.toList());

            if (CollectionUtils.isNotEmpty(mappings)) {

                ServletHolder defaultServletHolder = new ServletHolder(
                        configuration.getName(),
                        new DefaultServlet()
                );
                defaultServletHolder.setInitParameters(jettyConfiguration.getInitParameters());
                contextHandler.addServlet(
                        defaultServletHolder,
                        mappings.iterator().next()
                );
                contextHandler.setBaseResource(resourceCollection);
                ServletHandler servletHandler = defaultServletHolder.getServletHandler();
                if (mappings.size() > 1) {
                    ServletMapping m = new ServletMapping();
                    m.setServletName(configuration.getName());
                    m.setPathSpecs(mappings.subList(1, mappings.size()).toArray(StringUtils.EMPTY_STRING_ARRAY));
                    servletHandler.addServletMapping(m);
                }

                // going to be replaced
                defaultServletHolder.setInitParameter(RESOURCE_BASE, RESOURCE_BASE);
                // some defaults
                defaultServletHolder.setInitParameter("dirAllowed", StringUtils.FALSE);

            }

        }
        server.setHandler(contextHandler);

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
                    new HttpConnectionFactory(httpsConfig));
            https.setPort(securePort);
            server.addConnector(https);

        }
        final ServerConnector http = new ServerConnector(
                server,
                new HttpConnectionFactory(jettyConfiguration.getHttpConfiguration())
        );
        http.setPort(port);
        http.setHost(host);
        server.addConnector(
                http
        );

        return server;
    }

}
