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

import java.io.File;
import java.net.URL;
import java.util.List;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.env.Environment;
import io.micronaut.core.io.ResourceResolver;
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
import org.apache.catalina.webresources.TomcatURLStreamHandlerFactory;
import org.apache.tomcat.util.net.SSLHostConfig;

/**
 * Factory for the {@link Tomcat} instance.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Factory
public class TomcatFactory extends ServletServerFactory {

    static {
        TomcatURLStreamHandlerFactory.disable();
    }

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
     * @param configuration The servlet configuration
     * @return The Tomcat server
     */
    @Singleton
    @Primary
    protected Tomcat tomcatServer(Connector connector, MicronautServletConfiguration configuration) {
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
        servlet.setAsyncSupported(true);
        servlet.addMapping(configuration.getMapping());
        getStaticResourceConfigurations().forEach(config -> {
            servlet.addMapping(config.getMapping());
        });
        configuration.getMultipartConfigElement()
                .ifPresent(servlet::setMultipartConfigElement);

        SslConfiguration sslConfiguration = getSslConfiguration();
        if (sslConfiguration.isEnabled()) {
            String protocol = sslConfiguration.getProtocol().orElse("TLS");
            int sslPort = sslConfiguration.getPort();
            if (sslPort == SslConfiguration.DEFAULT_PORT && getEnvironment().getActiveNames().contains(Environment.TEST)) {
                sslPort = 0;
            }
            Connector httpsConnector = new Connector();
            httpsConnector.setPort(sslPort);
            httpsConnector.setSecure(true);
            httpsConnector.setScheme("https");
            httpsConnector.setProperty("clientAuth", "false");
            httpsConnector.setProperty("sslProtocol", protocol);
            httpsConnector.setProperty("SSLEnabled", "true");
            sslConfiguration.getCiphers().ifPresent(cyphers -> {
                SSLHostConfig[] sslHostConfigs = httpsConnector.findSslHostConfigs();
                for (SSLHostConfig sslHostConfig : sslHostConfigs) {
                    sslHostConfig.setCiphers(String.join(",", cyphers));
                }
            });
            sslConfiguration.getClientAuthentication().ifPresent(ca -> {
                switch (ca) {
                    case WANT -> httpsConnector.setProperty("clientAuth", "want");
                    case NEED -> httpsConnector.setProperty("clientAuth", "true");
                }
            });


            SslConfiguration.KeyStoreConfiguration keyStoreConfig = sslConfiguration.getKeyStore();
            keyStoreConfig.getPassword().ifPresent(s ->
                    httpsConnector.setProperty("keystorePass", s)
            );
            keyStoreConfig.getPath().ifPresent(path ->
                    setPathAttribute(httpsConnector, "keystoreFile", path)
            );
            keyStoreConfig.getProvider().ifPresent(provider ->
                    httpsConnector.setProperty("keystoreProvider", provider)
            );
            keyStoreConfig.getType().ifPresent(type ->
                    httpsConnector.setProperty("keystoreType", type)
            );

            SslConfiguration.TrustStoreConfiguration trustStore = sslConfiguration.getTrustStore();
            trustStore.getPassword().ifPresent(s ->
                    httpsConnector.setProperty("truststorePass", s)
            );
            trustStore.getPath().ifPresent(path ->
                    setPathAttribute(httpsConnector, "truststoreFile", path)
            );
            trustStore.getProvider().ifPresent(provider ->
                    httpsConnector.setProperty("truststoreProvider", provider)
            );
            trustStore.getType().ifPresent(type ->
                    httpsConnector.setProperty("truststoreType", type)
            );


            SslConfiguration.KeyConfiguration keyConfig = sslConfiguration.getKey();

            keyConfig.getAlias().ifPresent(s -> httpsConnector.setProperty("keyAlias", s));
            keyConfig.getPassword().ifPresent(s -> httpsConnector.setProperty("keyPass", s));

            tomcat.getService().addConnector(httpsConnector);
        }

        return tomcat;
    }

    /**
     * @return Create the protocol.
     */
    @Singleton
    @Primary
    protected Connector tomcatConnector() {
        final Connector tomcatConnector = getServerConfiguration().getTomcatConnector();
        tomcatConnector.setPort(getConfiguredPort());
        return tomcatConnector;
    }

    private void setPathAttribute(Connector httpsConnector, String attributeName, String path) {
        if (path.startsWith(ServletStaticResourceConfiguration.CLASSPATH_PREFIX)) {
            String res = path.substring(ServletStaticResourceConfiguration.CLASSPATH_PREFIX.length());
            URL resource = getEnvironment().getClassLoader().getResource(res);
            if (resource != null) {
                httpsConnector.setProperty(attributeName, resource.toString());
            }
        } else if (path.startsWith(ServletStaticResourceConfiguration.FILE_PREFIX)) {
            String res = path.substring(ServletStaticResourceConfiguration.FILE_PREFIX.length());
            httpsConnector.setProperty(attributeName, new File(res).getAbsolutePath());
        } else {
            httpsConnector.setProperty(attributeName, new File(path).getAbsolutePath());
        }
    }

}
