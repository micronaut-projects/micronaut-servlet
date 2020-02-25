package io.micronaut.servlet.undertow;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.env.Environment;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.server.exceptions.ServerStartupException;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;

import javax.inject.Singleton;
import javax.servlet.ServletException;

/**
 * Factory for the undertow server.
 *
 * @author graemerocher
 * @since 1.0
 */
@Factory
public class UndertowFactory extends ServletServerFactory {

    private final UndertowConfiguration configuration;

    /**
     * Default constructor.
     * @param resourceResolver The resource resolver
     * @param configuration The configuration
     * @param sslConfiguration The SSL configuration
     */
    public UndertowFactory(
            ResourceResolver resourceResolver,
            UndertowConfiguration configuration,
            SslConfiguration sslConfiguration) {
        super(resourceResolver, configuration, sslConfiguration);
        this.configuration = configuration;
    }

    @Singleton
    @Primary
    protected Undertow.Builder undertowBuilder(DeploymentInfo deploymentInfo) {
        final Undertow.Builder builder = configuration.getUndertowBuilder();
        int port = getConfiguredPort();
        String host = getConfiguredHost();
        builder.addHttpListener(
            port,
            host
        );

        final String cp = getContextPath();
        final DeploymentManager deploymentManager = Servlets.defaultContainer().addDeployment(deploymentInfo);
        deploymentManager
                .deploy();
        PathHandler path;
        try {
            path = Handlers.path(Handlers.redirect(cp))
                    .addPrefixPath(cp, deploymentManager.start());
        } catch (ServletException e) {
            throw new ServerStartupException("Error starting Undertow server: " + e.getMessage(), e);
        }
        builder.setHandler(path);

        final SslConfiguration sslConfiguration = getSslConfiguration();
        if (sslConfiguration.isEnabled()) {
            final int sslPort = sslConfiguration.getPort();
            build(sslConfiguration).ifPresent(sslContext -> builder.addHttpsListener(
                    sslPort,
                    host,
                    sslContext
            ));

        }
        return builder;
    }

    @Singleton
    @Primary
    protected Undertow undertowServer(Undertow.Builder builder) {
        return builder.build();
    }

    @Singleton
    @Primary
    protected DeploymentInfo deploymentInfo(Environment environment) {
        final String cp = getContextPath();

        return Servlets.deployment()
                    .setDeploymentName(Environment.MICRONAUT)
                    .setClassLoader(environment.getClassLoader())
                    .setContextPath(cp)
                    .addServlet(Servlets.servlet(
                            DefaultMicronautServlet.class
                    ).addMapping("/*"));
    }

}
