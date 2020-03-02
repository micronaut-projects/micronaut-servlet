package io.micronaut.servlet.tomcat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.env.Environment;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import io.micronaut.servlet.engine.server.ServletStaticResourceConfiguration;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.TomcatURLStreamHandlerFactory;

import javax.inject.Singleton;
import java.io.File;
import java.net.URL;
import java.util.List;

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
            if (sslPort == SslConfiguration.DEFAULT_PORT && getEnvironment().getActiveNames().contains(Environment.TEST)) {
                sslPort = SocketUtils.findAvailableTcpPort();
            }
            Connector httpsConnector = new Connector();
            httpsConnector.setPort(sslPort);
            httpsConnector.setSecure(true);
            httpsConnector.setScheme("https");
            httpsConnector.setAttribute("clientAuth", "false");
            httpsConnector.setAttribute("sslProtocol", protocol);
            httpsConnector.setAttribute("SSLEnabled", true);
            sslConfiguration.getCiphers().ifPresent(cyphers -> httpsConnector.setAttribute("cyphers", cyphers));
            sslConfiguration.getClientAuthentication().ifPresent(ca -> {
                switch (ca) {
                    case WANT:
                        httpsConnector.setAttribute("clientAuth", "want");
                        break;
                    default:
                    case NEED:
                        httpsConnector.setAttribute("clientAuth", "true");
                        break;

                }
            });


            SslConfiguration.KeyStoreConfiguration keyStoreConfig = sslConfiguration.getKeyStore();
            keyStoreConfig.getPassword().ifPresent(s ->
                    httpsConnector.setAttribute("keystorePass", s)
            );
            keyStoreConfig.getPath().ifPresent(path ->
                    setPathAttribute(httpsConnector, "keystoreFile", path)
            );
            keyStoreConfig.getProvider().ifPresent(provider ->
                    httpsConnector.setAttribute("keystoreProvider", provider)
            );
            keyStoreConfig.getType().ifPresent(type ->
                    httpsConnector.setAttribute("keystoreType", type)
            );

            SslConfiguration.TrustStoreConfiguration trustStore = sslConfiguration.getTrustStore();
            trustStore.getPassword().ifPresent(s ->
                    httpsConnector.setAttribute("truststorePass", s)
            );
            trustStore.getPath().ifPresent(path ->
                    setPathAttribute(httpsConnector, "truststoreFile", path)
            );
            trustStore.getProvider().ifPresent(provider ->
                    httpsConnector.setAttribute("truststoreProvider", provider)
            );
            trustStore.getType().ifPresent(type ->
                    httpsConnector.setAttribute("truststoreType", type)
            );


            SslConfiguration.KeyConfiguration keyConfig = sslConfiguration.getKey();

            keyConfig.getAlias().ifPresent(s -> httpsConnector.setAttribute("keyAlias", s));
            keyConfig.getPassword().ifPresent(s -> httpsConnector.setAttribute("keyPass", s));

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
                httpsConnector.setAttribute(attributeName, resource.toString());
            }
        } else if (path.startsWith(ServletStaticResourceConfiguration.FILE_PREFIX)) {
            String res = path.substring(ServletStaticResourceConfiguration.FILE_PREFIX.length());
            httpsConnector.setAttribute(attributeName, new File(res).getAbsolutePath());
        } else {
            httpsConnector.setAttribute(attributeName, new File(path).getAbsolutePath());
        }
    }

}
