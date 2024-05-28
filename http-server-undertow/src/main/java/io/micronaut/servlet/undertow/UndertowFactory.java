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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.server.exceptions.ServerStartupException;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import io.micronaut.servlet.engine.initializer.MicronautServletInitializer;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import io.micronaut.servlet.engine.server.ServletStaticResourceConfiguration;
import io.micronaut.web.router.Router;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.accesslog.AccessLogHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.InstanceHandle;
import io.undertow.servlet.api.ServletContainerInitializerInfo;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.xnio.Option;
import org.xnio.Options;

/**
 * Factory for the undertow server.
 *
 * @author graemerocher
 * @since 1.0
 */
@Factory
public class UndertowFactory extends ServletServerFactory {

    private final UndertowConfiguration configuration;
    private final Router router;

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
        this.router = applicationContext.findBean(Router.class).orElse(null);
    }

    @Override
    public UndertowConfiguration getServerConfiguration() {
        return (UndertowConfiguration) super.getServerConfiguration();
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


        final String cp = getContextPath();
        final DeploymentManager deploymentManager = Servlets.defaultContainer().addDeployment(deploymentInfo);
        deploymentManager
                .deploy();
        HttpHandler httpHandler;
        try {
            httpHandler = Handlers.path(Handlers.redirect(cp))
                    .addPrefixPath(cp, deploymentManager.start());
        } catch (ServletException e) {
            throw new ServerStartupException("Error starting Undertow server: " + e.getMessage(), e);
        }
        UndertowConfiguration serverConfiguration = getServerConfiguration();
        UndertowConfiguration.AccessLogConfiguration accessLogConfiguration = serverConfiguration.getAccessLogConfiguration().orElse(null);
        if (accessLogConfiguration != null) {
            httpHandler = new AccessLogHandler(
                httpHandler,
                accessLogConfiguration.builder.build(),
                accessLogConfiguration.getPattern(),
                getApplicationContext().getClassLoader()
            );
        }
        builder.setHandler(httpHandler);

        final SslConfiguration sslConfiguration = getSslConfiguration();
        if (sslConfiguration.isEnabled()) {
            int sslPort = sslConfiguration.getPort();
            if (sslPort == SslConfiguration.DEFAULT_PORT && getEnvironment().getActiveNames().contains(Environment.TEST)) {
                sslPort = 0; // random port
            }
            int finalSslPort = sslPort;
            SSLContext sslContext = build(sslConfiguration).orElse(null);
            if (sslContext != null) {
                builder.addHttpsListener(
                    finalSslPort,
                    host,
                    sslContext
                );
                if (getServerConfiguration().isDualProtocol()) {
                    builder.addHttpListener(
                        port,
                        host
                    );
                }
                applyAdditionalPorts(builder, host, port, sslContext);
            } else {
                builder.addHttpListener(
                    port,
                    host
                );
                applyAdditionalPorts(builder, host, port, null);
            }

        } else {
            builder.addHttpListener(
                port,
                host
            );
            applyAdditionalPorts(builder, host, port, null);
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

    private void applyAdditionalPorts(Undertow.Builder builder, String host, int serverPort, @Nullable SSLContext sslContext) {
        if (router != null) {
            Set<Integer> exposedPorts = router.getExposedPorts();
            if (CollectionUtils.isNotEmpty(exposedPorts)) {
                for (Integer exposedPort : exposedPorts) {
                    if (!exposedPort.equals(serverPort)) {
                        addListener(builder, host, sslContext, exposedPort);
                    }
                }
            }
        }
    }

    private static void addListener(Undertow.Builder builder, String host, SSLContext sslContext, Integer exposedPort) {
        if (sslContext != null) {
            builder.addHttpsListener(exposedPort, host, sslContext);
        } else {
            builder.addHttpListener(exposedPort, host);
        }
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
     * @deprecated Use {@link ##deploymentInfo(MicronautServletConfiguration, Collection)}
     */
    @Deprecated(forRemoval = true, since = "4.8.0")
    protected DeploymentInfo deploymentInfo(MicronautServletConfiguration servletConfiguration) {
        return deploymentInfo(servletConfiguration, getApplicationContext().getBeansOfType(ServletContainerInitializer.class));
    }

    /**
     * The deployment info bean.
     *
     * @param servletConfiguration The servlet configuration.
     * @param servletInitializers The servlet initializer
     * @return The deployment info
     */
    @Singleton
    @Primary
    protected DeploymentInfo deploymentInfo(MicronautServletConfiguration servletConfiguration, Collection<ServletContainerInitializer> servletInitializers) {
        final String cp = getContextPath();
        for (ServletContainerInitializer servletInitializer : servletInitializers) {
            if (servletInitializer instanceof MicronautServletInitializer micronautServletInitializer) {
                getStaticResourceConfigurations().forEach(config ->
                    micronautServletInitializer.addMicronautServletMapping(config.getMapping())
                );
            }
        }
        DeploymentInfo deploymentInfo = Servlets.deployment()
            .setDeploymentName(servletConfiguration.getName())
            .setClassLoader(getEnvironment().getClassLoader())
            .setContextPath(cp);
        for (ServletContainerInitializer servletInitializer : servletInitializers) {
            deploymentInfo
                .addServletContainerInitializer(new ServletContainerInitializerInfo(
                    servletInitializer.getClass(),
                    () -> new InstanceHandle<>() {
                        @Override
                        public ServletContainerInitializer getInstance() {
                            return servletInitializer;
                        }

                        @Override
                        public void release() {

                        }
                    },
                    Set.of(servletInitializer.getClass())
                ));
        }
        return deploymentInfo;
    }

}
