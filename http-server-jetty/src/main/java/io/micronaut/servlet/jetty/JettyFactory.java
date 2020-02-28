package io.micronaut.servlet.jetty;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import io.micronaut.servlet.engine.server.ServletStaticResourceConfiguration;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import javax.inject.Singleton;
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
     * @return The Jetty server bean
     */
    @Singleton
    @Primary
    protected Server jettyServer(ApplicationContext applicationContext) {
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
                "/"
        );
        servletHolder.setAsyncSupported(true);

        jettyConfiguration.getMultipartConfiguration().ifPresent(multipartConfiguration ->
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

                String servletName = DefaultServlet.class.getSimpleName();
                ServletHolder defaultServletHolder = new ServletHolder(
                        servletName,
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
                    m.setServletName(servletName);
                    m.setPathSpecs(mappings.subList(1, mappings.size()).toArray(StringUtils.EMPTY_STRING_ARRAY));
                    servletHandler.addServletMapping(m);
                }

                // going to be replaced
                defaultServletHolder.setInitParameter(RESOURCE_BASE, RESOURCE_BASE);

            }

        }
            server.setHandler(contextHandler);

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
