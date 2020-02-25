package io.micronaut.servlet.undertow;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.http.server.exceptions.ServerStartupException;
import io.micronaut.http.ssl.SslBuilder;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;

import javax.inject.Singleton;
import javax.net.ssl.*;
import javax.servlet.ServletException;
import java.security.SecureRandom;
import java.util.Optional;

@Factory
public class UndertowFactory extends SslBuilder<SSLContext> {

    private final HttpServerConfiguration configuration;
    private final SslConfiguration sslConfiguration;

    public UndertowFactory(
            ResourceResolver resourceResolver,
            HttpServerConfiguration configuration,
            SslConfiguration sslConfiguration) {
        super(resourceResolver);
        this.configuration = configuration;
        this.sslConfiguration = sslConfiguration;
    }

    @Singleton
    protected Undertow.Builder undertowBuilder(Environment environment) {
        final Undertow.Builder builder = Undertow.builder();
        int port = configuration.getPort().map( p ->
                p == -1 ? SocketUtils.findAvailableTcpPort() : p
        ).orElse(8080);
        String host = configuration
                .getHost()
                .orElseGet(() -> Optional.ofNullable(System.getenv("HOST")).orElse("localhost"));
        builder.addHttpListener(
            port,
            host
        );

        final String contextPath = configuration.getContextPath();
        final String cp = contextPath != null ? contextPath : "/";
        final DeploymentInfo deploymentInfo = Servlets.deployment()
                .setDeploymentName(Environment.MICRONAUT)
                .setClassLoader(environment.getClassLoader())
                .setContextPath(cp)
                .addServlet(Servlets.servlet(
                        DefaultMicronautServlet.class
                ).addMapping("/*"));
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
    protected Undertow undertowServer(Undertow.Builder builder) {
        return builder.build();
    }

    @Override
    public Optional<SSLContext> build(SslConfiguration ssl) {
        if (sslConfiguration.isEnabled()) {
            final String protocol = sslConfiguration
                    .getProtocol().orElseThrow(() -> new ServerStartupException("No SSL protocal specified"));

            try {
                final SSLContext sslContext = SSLContext.getInstance(protocol);
                final KeyManagerFactory keyManagerFactory = getKeyManagerFactory(ssl);
                final KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();
                final TrustManagerFactory trustManagerFactory = getTrustManagerFactory(ssl);
                final TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
                sslContext.init(
                        keyManagers,
                        trustManagers,
                        new SecureRandom()
                );
            } catch (Throwable e) {
                throw new HttpServerException("HTTPS configuration error: " + e.getMessage(), e);
            }
        }
        return Optional.empty();
    }
}
