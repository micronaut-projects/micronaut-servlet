package io.micronaut.servlet.tomcat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import javax.inject.Singleton;
import java.io.File;

/**
 * Factory for the {@link Tomcat} instance.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Factory
public class TomcatFactory extends ServletServerFactory {

    /**
     * Default constructor.
     *
     * @param resourceResolver    The resource resolver
     * @param serverConfiguration The server config
     * @param sslConfiguration    The SSL config
     * @param applicationContext  The app context
     */
    protected TomcatFactory(
            ResourceResolver resourceResolver,
            TomcatConfiguration serverConfiguration,
            SslConfiguration sslConfiguration,
            ApplicationContext applicationContext) {
        super(resourceResolver, serverConfiguration, sslConfiguration, applicationContext);
    }

    @Override
    public TomcatConfiguration getServerConfiguration() {
        return (TomcatConfiguration) super.getServerConfiguration();
    }

    /**
     * The Tomcat server bean.
     *
     * @param connector          The connector
     * @return The Tomcat server
     */
    @Singleton
    @Primary
    protected Tomcat tomcatServer(Connector connector) {
        Tomcat tomcat = new Tomcat();
        tomcat.setHostname(getConfiguredHost());
        final String contextPath = getContextPath();
        tomcat.getHost().setAutoDeploy(false);
        tomcat.setConnector(connector);
        final String cp = contextPath != null && !contextPath.equals("/") ? contextPath : "";
        final Context context = tomcat.addContext(cp, "/");
        final Wrapper servlet = Tomcat.addServlet(
                context,
                "micronaut",
                new DefaultMicronautServlet(getApplicationContext())
        );
        servlet.addMapping("/*");
        getServerConfiguration()
                .getMultipartConfiguration()
                .ifPresent(servlet::setMultipartConfigElement);

        SslConfiguration sslConfiguration = getSslConfiguration();
        if (sslConfiguration.isEnabled()) {
            String protocol = sslConfiguration.getProtocol().orElse("TLS");
            int sslPort = sslConfiguration.getPort();

            SslConfiguration.KeyStoreConfiguration keyStoreConfig = sslConfiguration.getKeyStore();
            SslConfiguration.KeyConfiguration keyConfig = sslConfiguration.getKey();
            Connector httpsConnector = new Connector();
            httpsConnector.setPort(sslPort);
            httpsConnector.setSecure(true);
            httpsConnector.setScheme("https");

            keyConfig.getAlias().ifPresent(s -> httpsConnector.setAttribute("keyAlias", s));
            keyStoreConfig.getPassword().ifPresent(s -> httpsConnector.setAttribute("keystorePass", s));
            keyStoreConfig.getPath().ifPresent(s ->
                    httpsConnector.setAttribute("keystoreFile", new File(s).getAbsolutePath())
            );

            httpsConnector.setAttribute("clientAuth", "false");
            httpsConnector.setAttribute("sslProtocol", protocol);
            httpsConnector.setAttribute("SSLEnabled", true);
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

}
