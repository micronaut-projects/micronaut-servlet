package io.micronaut.servlet.jetty;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import javax.inject.Singleton;

/**
 * Factory for the Jetty server.
 *
 * @author graemerocher
 * @since 1.0
 */
@Factory
public class JettyFactory extends ServletServerFactory {

    private final JettyConfiguration jettyConfiguration;

    /**
     * Default constructor.
     * @param resourceResolver The resource resolver
     * @param serverConfiguration The server config
     * @param sslConfiguration The SSL config
     */
    public JettyFactory(
            ResourceResolver resourceResolver,
            JettyConfiguration serverConfiguration,
            SslConfiguration sslConfiguration) {
        super(resourceResolver, serverConfiguration, sslConfiguration);
        this.jettyConfiguration = serverConfiguration;
    }

    /**
     * Builds the Jetty server bean.
     * @return The Jetty server bean
     */
    @Singleton
    @Primary
    protected Server jettyServer() {
        final String host = getConfiguredHost();
        final Integer port = getConfiguredPort();
        Server server = new Server();
        final ServletContextHandler handler = new ServletContextHandler(server, getContextPath(), false, false);
        final ServletHolder servletHolder = handler.addServlet(
                DefaultMicronautServlet.class,
                "/"
        );
        servletHolder.setAsyncSupported(true);

        jettyConfiguration.getMultipartConfiguration().ifPresent(multipartConfiguration ->
                servletHolder.getRegistration().setMultipartConfig(multipartConfiguration)
        );

        server.setHandler(handler);

        final SslConfiguration sslConfiguration = getSslConfiguration();
        if (sslConfiguration.isEnabled()) {
            final HttpConfiguration httpConfig = jettyConfiguration.getHttpConfiguration();
            final int securePort = sslConfiguration.getPort();
            httpConfig.setSecurePort(securePort);

            SslContextFactory sslContextFactory = new SslContextFactory.Server();
            final SslConfiguration.KeyStoreConfiguration keyStoreConfig = sslConfiguration.getKeyStore();
            sslContextFactory.setKeyStorePath(keyStoreConfig.getPath()
                .orElseThrow(() -> new HttpServerException("Invalid SSL configuration: Missing key store path")));
            sslContextFactory.setKeyStorePassword(keyStoreConfig.getPassword()
                    .orElseThrow(() -> new HttpServerException("Invalid SSL configuration: Missing key store password")));
            sslContextFactory.setKeyManagerPassword(sslConfiguration.getKey().getPassword().orElseThrow(() -> new HttpServerException("Invalid SSL configuration: Missing key manager password")));

            HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
            httpsConfig.addCustomizer(new SecureRequestCustomizer());
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
