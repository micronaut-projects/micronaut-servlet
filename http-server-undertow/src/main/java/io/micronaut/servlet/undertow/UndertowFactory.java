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
package io.micronaut.servlet.undertow;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.env.Environment;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.http.server.exceptions.ServerStartupException;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import io.micronaut.servlet.engine.server.ServletStaticResourceConfiguration;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.*;
import org.xnio.Option;
import org.xnio.Options;

import jakarta.inject.Singleton;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import java.util.List;
import java.util.Map;

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
     *
     * @param resourceResolver             The resource resolver
     * @param configuration                The configuration
     * @param sslConfiguration             The SSL configuration
     * @param applicationContext           The app context
     * @param staticResourceConfigurations The static resource configs
     */
    public UndertowFactory(
            ResourceResolver resourceResolver,
            UndertowConfiguration configuration,
            SslConfiguration sslConfiguration,
            ApplicationContext applicationContext,
            List<ServletStaticResourceConfiguration> staticResourceConfigurations) {
        super(resourceResolver, configuration, sslConfiguration, applicationContext, staticResourceConfigurations);
        this.configuration = configuration;
    }

    /**
     * The undertow builder bean.
     *
     * @param deploymentInfo       The deployment info
     * @param servletConfiguration The servlet configuration
     * @return The builder
     */
    @Singleton
    @Primary
    protected Undertow.Builder undertowBuilder(DeploymentInfo deploymentInfo, MicronautServletConfiguration servletConfiguration) {
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
            int sslPort = sslConfiguration.getPort();
            if (sslPort == SslConfiguration.DEFAULT_PORT && getEnvironment().getActiveNames().contains(Environment.TEST)) {
                sslPort = SocketUtils.findAvailableTcpPort();
            }
            int finalSslPort = sslPort;
            build(sslConfiguration).ifPresent(sslContext ->
                    builder.addHttpsListener(
                            finalSslPort,
                            host,
                            sslContext
                    ));

        }

        Map<String, String> serverOptions = configuration.getServerOptions();
        serverOptions.forEach((key, value) -> {
            Object opt = ReflectionUtils.findDeclaredField(UndertowOptions.class, key)
                    .map(field -> {
                        field.setAccessible(true);
                        try {
                            return field.get(UndertowOptions.class);
                        } catch (IllegalAccessException e) {
                            return null;
                        }
                    }).orElse(null);

            if (opt instanceof Option) {
                //noinspection unchecked
                builder.setServerOption((Option<Object>) opt, value);
            } else {
                builder.setServerOption(Option.simple(UndertowOptions.class, key, String.class), value);
            }
        });
        Map<String, String> workerOptions = configuration.getWorkerOptions();
        workerOptions.forEach((key, value) -> {
            Object opt = getOptionValue(key);

            if (opt instanceof Option) {
                //noinspection unchecked
                builder.setWorkerOption((Option<Object>) opt, value);
            } else {
                builder.setWorkerOption(Option.simple(Options.class, key, String.class), value);
            }
        });
        Map<String, String> socketOptions = configuration.getSocketOptions();
        socketOptions.forEach((key, value) -> {
            Object opt = getOptionValue(key);

            if (opt instanceof Option) {
                //noinspection unchecked
                builder.setSocketOption((Option<Object>) opt, value);
            } else {
                builder.setSocketOption(Option.simple(Options.class, key, String.class), value);
            }
        });
        return builder;
    }

    private Object getOptionValue(String key) {
        return ReflectionUtils.findDeclaredField(Options.class, key)
                .map(field -> {
                    field.setAccessible(true);
                    try {
                        return field.get(Options.class);
                    } catch (IllegalAccessException e) {
                        return null;
                    }
                }).orElse(null);
    }

    /**
     * The undertow bean.
     *
     * @param builder The builder
     * @return The undertow bean
     */
    @Singleton
    @Primary
    protected Undertow undertowServer(Undertow.Builder builder) {
        return builder.build();
    }

    /**
     * The deployment info bean.
     *
     * @param servletConfiguration The servlet configuration.
     * @return The deployment info
     */
    @Singleton
    @Primary
    protected DeploymentInfo deploymentInfo(MicronautServletConfiguration servletConfiguration) {
        final String cp = getContextPath();

        ServletInfo servletInfo = Servlets.servlet(
                servletConfiguration.getName(), DefaultMicronautServlet.class, () -> new InstanceHandle<Servlet>() {

                    private DefaultMicronautServlet instance;

                    @Override
                    public Servlet getInstance() {
                        instance = new DefaultMicronautServlet(getApplicationContext());
                        return instance;
                    }

                    @Override
                    public void release() {
                        if (instance != null) {
                            instance.destroy();
                        }
                    }
                }
        );
        servletInfo.setAsyncSupported(true);
        servletInfo.addMapping(servletConfiguration.getMapping());
        getStaticResourceConfigurations().forEach(config -> {
            servletInfo.addMapping(config.getMapping());
        });
        final DeploymentInfo deploymentInfo = Servlets.deployment()
                .setDeploymentName(servletConfiguration.getName())
                .setClassLoader(getEnvironment().getClassLoader())
                .setContextPath(cp)
                .addServlet(servletInfo);
        servletConfiguration.getMultipartConfigElement().ifPresent(deploymentInfo::setDefaultMultipartConfig);
        return deploymentInfo;
    }

}
