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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.StringUtils;
import jakarta.inject.Named;
import java.io.File;
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
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for the {@link Tomcat} instance.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Factory
public class TomcatFactory extends ServletServerFactory {

    private static final String HTTPS = "HTTPS";
    private static final Logger LOG = LoggerFactory.getLogger(TomcatFactory.class);

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
    }

    @Override
    public TomcatConfiguration getServerConfiguration() {
        return (TomcatConfiguration) super.getServerConfiguration();
    }

    /**
     * The Tomcat server bean.
     *
     * @param connector The connector
     * @param httpsConnector The HTTPS connector
     * @param configuration The servlet configuration
     * @return The Tomcat server
     */
    @Singleton
    @Primary
    protected Tomcat tomcatServer(
        Connector connector,
        @Named(HTTPS) @Nullable Connector httpsConnector,
        MicronautServletConfiguration configuration) {
        configuration.setAsyncFileServingEnabled(false);
        Tomcat tomcat = new Tomcat();
        tomcat.setHostname(getConfiguredHost());
        final String contextPath = getContextPath();
        tomcat.getHost().setAutoDeploy(false);
        tomcat.setConnector(connector);
        final String cp = contextPath != null && !contextPath.equals("/") ? contextPath : "";
        final Context context = tomcat.addContext(cp, "/");


        // add required folder
        File docBaseFile = new File(context.getDocBase());
        if (!docBaseFile.isAbsolute()) {
            docBaseFile = new File(((org.apache.catalina.Host) context.getParent()).getAppBaseFile(), docBaseFile.getPath());
        }
        docBaseFile.mkdirs();

        final Wrapper servlet = Tomcat.addServlet(
                context,
                configuration.getName(),
                new DefaultMicronautServlet(getApplicationContext())
        );

        boolean isAsync = configuration.isAsyncSupported();
        if (Boolean.FALSE.equals(isAsync)) {
            LOG.debug("Servlet async mode is disabled");
        }
        servlet.setAsyncSupported(isAsync);
        servlet.addMapping(configuration.getMapping());
        getStaticResourceConfigurations().forEach(config ->
            servlet.addMapping(config.getMapping())
        );
        configuration.getMultipartConfigElement()
                .ifPresent(servlet::setMultipartConfigElement);

        if (httpsConnector != null) {
            tomcat.getService().addConnector(httpsConnector);
        }

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
        httpsConnector.setProperty("clientAuth", "false");
        httpsConnector.setProperty("sslProtocol", protocol);
        httpsConnector.setProperty("SSLEnabled", "true");
        sslConfiguration.getCiphers().ifPresent(cyphers ->
            sslHostConfig.setCiphers(String.join(",", cyphers))
        );
        sslConfiguration.getClientAuthentication().ifPresent(ca ->
            httpsConnector.setProperty("clientAuth", ca == ClientAuthentication.WANT ? "want" : "true")
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
