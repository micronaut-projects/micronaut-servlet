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
package io.micronaut.servlet.tomcat;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpVersion;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.web.router.Router;
import jakarta.inject.Named;
import io.micronaut.servlet.engine.initializer.MicronautServletInitializer;
import jakarta.servlet.ServletContainerInitializer;
import java.io.File;
import java.util.Collection;
import java.util.List;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.env.Environment;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.ssl.ClientAuthentication;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import io.micronaut.servlet.engine.server.ServletStaticResourceConfiguration;
import jakarta.inject.Singleton;
import java.util.Set;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.ContainerBase;
import org.apache.catalina.core.StandardThreadExecutor;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

/**
 * Factory for the {@link Tomcat} instance.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Factory
public class TomcatFactory extends ServletServerFactory {

    private static final String HTTPS = "HTTPS";
    private static final String CLIENT_AUTH = "clientAuth";
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
    protected TomcatFactory(
        ResourceResolver resourceResolver,
        TomcatConfiguration serverConfiguration,
        SslConfiguration sslConfiguration,
        ApplicationContext applicationContext,
        List<ServletStaticResourceConfiguration> staticResourceConfigurations) {
        super(resourceResolver, serverConfiguration, sslConfiguration, applicationContext, staticResourceConfigurations);
        this.router = applicationContext.findBean(Router.class).orElse(null);
    }

    @Override
    public TomcatConfiguration getServerConfiguration() {
        return (TomcatConfiguration) super.getServerConfiguration();
    }

    /**
     * The Tomcat server bean.
     *
     * @param connector     The connector
     * @param configuration The servlet configuration
     * @return The Tomcat server
     */
    protected Tomcat tomcatServer(Connector connector, MicronautServletConfiguration configuration) {
        return tomcatServer(
            connector,
            getApplicationContext().getBean(Connector.class, Qualifiers.byName(HTTPS)),
            configuration,
            getApplicationContext().getBeansOfType(ServletContainerInitializer.class));
    }

    /**
     * The Tomcat server bean.
     *
     * @param connector          The connector
     * @param httpsConnector     The HTTPS connectors
     * @param configuration      The servlet configuration
     * @param servletInitializers The servlet initializer
     * @return The Tomcat server
     */
    @Singleton
    @Primary
    protected Tomcat tomcatServer(
        Connector connector,
        @Named(HTTPS) @Nullable Connector httpsConnector,
        MicronautServletConfiguration configuration,
        Collection<ServletContainerInitializer> servletInitializers) {
        configuration.setAsyncFileServingEnabled(false);

        Tomcat tomcat = newTomcat();
        if (configuration.getMaxThreads() != null) {
            StandardThreadExecutor executor = new StandardThreadExecutor();
            executor.setName("tomcatThreadPool");
            executor.setMaxThreads(configuration.getMaxThreads());
            if (configuration.getMinThreads() != null) {
                executor.setMinSpareThreads(configuration.getMinThreads());
            }
            tomcat.getService().addExecutor(executor);
            if (connector != null) {
                connector.getProtocolHandler().setExecutor(executor);
            }
            if (httpsConnector != null) {
                httpsConnector.getProtocolHandler().setExecutor(executor);
            }
        }
        final Context context = newTomcatContext(tomcat);

        configureServletInitializer(context, servletInitializers);
        configureConnectors(tomcat, connector, httpsConnector);

        TomcatConfiguration serverConfiguration = getServerConfiguration();
        serverConfiguration.getAccessLogConfiguration().ifPresent(accessValve -> {
            if (accessValve.isEnabled()) {
                Container[] children = tomcat.getHost().findChildren();
                for (Container child : children) {
                    if (child instanceof ContainerBase containerBase) {
                        containerBase.addValve(accessValve);
                    }
                }
            }
        });

        return tomcat;
    }

    /**
     * Configure the Micronaut servlet initializer.
     *
     * @param context            The context
     * @param servletInitializers The initializers
     */
    protected void configureServletInitializer(Context context, Collection<ServletContainerInitializer> servletInitializers) {
        for (ServletContainerInitializer servletInitializer : servletInitializers) {
            if (servletInitializer instanceof MicronautServletInitializer micronautServletInitializer) {
                getStaticResourceConfigurations().forEach(config ->
                    micronautServletInitializer.addMicronautServletMapping(config.getMapping())
                );
                context.addServletContainerInitializer(
                    servletInitializer, Set.of(DefaultMicronautServlet.class)
                );
            } else {
                context.addServletContainerInitializer(
                    servletInitializer, Set.of()
                );
            }
        }
    }

    /**
     * Configures the available connectors.
     *
     * @param tomcat         The tomcat instance
     * @param httpConnector  The HTTP connector
     * @param httpsConnector The HTTPS connector
     */
    protected void configureConnectors(@NonNull Tomcat tomcat, @NonNull Connector httpConnector, @Nullable Connector httpsConnector) {
        TomcatConfiguration serverConfiguration = getServerConfiguration();
        HttpVersion httpVersion = getServerConfiguration().getHttpVersion();
        if (httpVersion == HttpVersion.HTTP_2_0) {
            httpConnector.addUpgradeProtocol(new Http2Protocol());
        }
        if (httpsConnector != null) {
            tomcat.getService().addConnector(httpsConnector);
            if (serverConfiguration.isDualProtocol()) {
                tomcat.getService().addConnector(httpConnector);
            }
            applyAdditionalPorts(tomcat, httpsConnector);
        } else {
            tomcat.setConnector(httpConnector);
            applyAdditionalPorts(tomcat, httpConnector);
        }
    }

    private void applyAdditionalPorts(Tomcat server, Connector serverConnector) {
        if (router != null) {
            Set<Integer> exposedPorts = router.getExposedPorts();
            if (CollectionUtils.isNotEmpty(exposedPorts)) {
                for (Integer exposedPort : exposedPorts) {
                    if (!exposedPort.equals(serverConnector.getLocalPort())) {
                        Connector newConnector = cloneConnectorSettings(serverConnector);
                        newConnector.setPort(exposedPort);
                        server.getService().addConnector(newConnector);
                    }
                }
            }
        }
    }

    private static Connector cloneConnectorSettings(Connector serverConnector) {
        Connector newConnector = new Connector(serverConnector.getProtocol());
        ProtocolHandler protocolHandler = serverConnector.getProtocolHandler();
        SSLHostConfig[] sslHostConfigs = protocolHandler.findSslHostConfigs();
        for (SSLHostConfig sslHostConfig : sslHostConfigs) {
            newConnector.addSslHostConfig(sslHostConfig);
            newConnector.setSecure(true);
            newConnector.setScheme("https");
            newConnector.setProperty(CLIENT_AUTH, "false");
            newConnector.setProperty("sslProtocol", "TLS");
            newConnector.setProperty("SSLEnabled", "true");
        }
        newConnector.setAllowBackslash(serverConnector.getAllowBackslash());
        newConnector.setAllowTrace(serverConnector.getAllowTrace());
        newConnector.setAsyncTimeout(serverConnector.getAsyncTimeout());
        newConnector.setDiscardFacades(serverConnector.getDiscardFacades());
        newConnector.setEnableLookups(serverConnector.getEnableLookups());
        newConnector.setSecure(serverConnector.getSecure());
        newConnector.setScheme(serverConnector.getScheme());
        newConnector.setEnforceEncodingInGetWriter(serverConnector.getEnforceEncodingInGetWriter());
        newConnector.setMaxCookieCount(serverConnector.getMaxCookieCount());
        newConnector.setMaxPostSize(serverConnector.getMaxPostSize());
        newConnector.setMaxParameterCount(serverConnector.getMaxParameterCount());
        newConnector.setMaxSavePostSize(serverConnector.getMaxSavePostSize());
        newConnector.setParseBodyMethods(serverConnector.getParseBodyMethods());
        newConnector.setRejectSuspiciousURIs(serverConnector.getRejectSuspiciousURIs());
        newConnector.setUseIPVHosts(serverConnector.getUseIPVHosts());
        return newConnector;
    }

    /**
     * Create a new context.
     *
     * @param tomcat The tomcat instance
     * @return The context
     */
    protected @NonNull Context newTomcatContext(@NonNull Tomcat tomcat) {
        final String contextPath = getContextPath();
        final String cp = contextPath != null && !contextPath.equals("/") ? contextPath : "";
        final Context context = tomcat.addContext(cp, "/");
        // add required folder
        File docBaseFile = new File(context.getDocBase());
        if (!docBaseFile.isAbsolute()) {
            docBaseFile = new File(((org.apache.catalina.Host) context.getParent()).getAppBaseFile(), docBaseFile.getPath());
        }
        docBaseFile.mkdirs();
        return context;
    }

    /**
     * Create a new tomcat server.
     *
     * @return The tomcat server
     */
    protected @NonNull Tomcat newTomcat() {
        Tomcat tomcat = new Tomcat();
        tomcat.getHost().setAutoDeploy(false);
        tomcat.setHostname(getConfiguredHost());
        return tomcat;
    }

    /**
     * @return Create the connector.
     */
    @Singleton
    @Primary
    protected Connector tomcatConnector() {
        final Connector tomcatConnector = getServerConfiguration().getTomcatConnector();
        tomcatConnector.setPort(getConfiguredPort());
        return tomcatConnector;
    }

    /**
     * The HTTPS connector.
     *
     * @param sslConfiguration The SSL configuration.
     * @return The SSL connector
     */
    @Singleton
    @Named(HTTPS)
    @Requires(property = SslConfiguration.PREFIX + ".enabled", value = StringUtils.TRUE)
    protected Connector sslConnector(SslConfiguration sslConfiguration) {
        String protocol = sslConfiguration.getProtocol().orElse("TLS");
        int sslPort = sslConfiguration.getPort();
        if (sslPort == SslConfiguration.DEFAULT_PORT && getEnvironment().getActiveNames().contains(Environment.TEST)) {
            sslPort = 0;
        }
        Connector httpsConnector = new Connector();
        SSLHostConfig sslHostConfig = new SSLHostConfig();
        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.UNDEFINED);
        sslHostConfig.addCertificate(certificate);
        httpsConnector.addSslHostConfig(sslHostConfig);
        httpsConnector.setPort(sslPort);
        httpsConnector.setSecure(true);
        httpsConnector.setScheme("https");
        httpsConnector.setProperty(CLIENT_AUTH, "false");
        httpsConnector.setProperty("sslProtocol", protocol);
        httpsConnector.setProperty("SSLEnabled", "true");
        sslConfiguration.getCiphers().ifPresent(cyphers ->
            sslHostConfig.setCiphers(String.join(",", cyphers))
        );
        sslConfiguration.getClientAuthentication().ifPresent(ca ->
            httpsConnector.setProperty(CLIENT_AUTH, ca == ClientAuthentication.WANT ? "want" : "true")
        );


        SslConfiguration.KeyStoreConfiguration keyStoreConfig = sslConfiguration.getKeyStore();
        keyStoreConfig.getPassword().ifPresent(certificate::setCertificateKeystorePassword);
        keyStoreConfig.getPath().ifPresent(certificate::setCertificateKeystoreFile);
        keyStoreConfig.getProvider().ifPresent(certificate::setCertificateKeystorePassword);
        keyStoreConfig.getType().ifPresent(certificate::setCertificateKeystoreType);

        SslConfiguration.TrustStoreConfiguration trustStore = sslConfiguration.getTrustStore();
        trustStore.getPassword().ifPresent(sslHostConfig::setTruststorePassword);
        trustStore.getPath().ifPresent(sslHostConfig::setTruststoreFile);
        trustStore.getProvider().ifPresent(sslHostConfig::setTruststoreProvider);
        trustStore.getType().ifPresent(sslHostConfig::setTruststoreType);

        SslConfiguration.KeyConfiguration keyConfig = sslConfiguration.getKey();
        keyConfig.getAlias().ifPresent(certificate::setCertificateKeyAlias);
        keyConfig.getPassword().ifPresent(certificate::setCertificateKeyPassword);
        return httpsConnector;
    }
}
