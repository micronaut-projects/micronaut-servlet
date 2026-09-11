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
import org.jspecify.annotations.Nullable;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.server.exceptions.ServerStartupException;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import io.micronaut.servlet.engine.initializer.MicronautServletInitializer;
import io.micronaut.scheduling.LoomSupport;
import io.micronaut.servlet.engine.ServletCompressionConfiguration;
import io.micronaut.servlet.http.server.ServletServerFactory;
import io.micronaut.servlet.http.server.ServletStaticResourceConfiguration;
import io.micronaut.web.router.Router;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.accesslog.AccessLogHandler;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import io.undertow.predicate.Predicate;
import io.undertow.util.Headers;

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
import java.util.concurrent.Executors;
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
    /**
     * Priority of the gzip encoder; there is only one, so any positive value will do.
     */
    private static final int GZIP_ENCODING_PRIORITY = 100;

    private final UndertowConfiguration configuration;
    private final @Nullable Router router;

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
        // compression sits inside the access log, so the log records the bytes that actually went out
        httpHandler = compressIfEnabled(httpHandler);
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

        if (getServerConfiguration().getHttpVersion() == io.micronaut.http.HttpVersion.HTTP_2_0) {
            // Undertow supports HTTP/2 natively, over TLS via ALPN and in the clear via the h2c upgrade, but only
            // when asked; Jetty and Tomcat already honour micronaut.server.http-version, so this brings Undertow level
            builder.setServerOption(UndertowOptions.ENABLE_HTTP2, true);
        }

        if (servletConfiguration.getMaxThreads() != null) {
            builder.setServerOption(Options.WORKER_TASK_MAX_THREADS, servletConfiguration.getMaxThreads());
            if (servletConfiguration.getMinThreads() != null) {
                builder.setServerOption(Options.WORKER_TASK_CORE_THREADS, servletConfiguration.getMinThreads());
            }
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

    /**
     * Wraps the handler so responses are compressed when the client accepts it.
     *
     * <p>Undertow compresses responses itself, so the shared configuration is wired to its encoder rather than
     * reimplemented: it already settles HEAD, ranges, already encoded bodies and the {@code Vary} header.</p>
     *
     * @param httpHandler The handler serving requests
     * @return The handler to install on the builder
     */
    private HttpHandler compressIfEnabled(HttpHandler httpHandler) {
        ServletCompressionConfiguration compression = getApplicationContext()
            .findBean(ServletCompressionConfiguration.class)
            .orElse(null);
        if (compression == null || !compression.isEnabled()) {
            return httpHandler;
        }
        Set<String> contentTypes = compression.getContentTypes();
        long threshold = compression.getThreshold();
        // Undertow's own size predicates read the request, so the response is inspected here instead: a body is
        // worth compressing when its type benefits and it is large enough to pay for the encoding
        Predicate compressible = exchange -> {
            String contentType = exchange.getResponseHeaders().getFirst(Headers.CONTENT_TYPE);
            if (contentType == null) {
                return false;
            }
            int parameters = contentType.indexOf(';');
            String bare = (parameters == -1 ? contentType : contentType.substring(0, parameters)).trim();
            if (contentTypes.stream().noneMatch(bare::equalsIgnoreCase)) {
                return false;
            }
            String length = exchange.getResponseHeaders().getFirst(Headers.CONTENT_LENGTH);
            if (length == null) {
                return true;
            }
            try {
                return Long.parseLong(length) >= threshold;
            } catch (NumberFormatException _) {
                return true;
            }
        };
        return new EncodingHandler(new ContentEncodingRepository()
            .addEncodingHandler(
                "gzip",
                new GzipEncodingProvider(),
                GZIP_ENCODING_PRIORITY,
                compressible
            ))
            .setNext(httpHandler);
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

    private static void addListener(Undertow.Builder builder, String host, @Nullable SSLContext sslContext, Integer exposedPort) {
        if (sslContext != null) {
            builder.addHttpsListener(exposedPort, host, sslContext);
        } else {
            builder.addHttpListener(exposedPort, host);
        }
    }

    private @Nullable Object getOptionValue(String key) {
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
     * @deprecated Use {@link #deploymentInfo(MicronautServletConfiguration, Collection)}
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
        if (servletConfiguration.isEnableVirtualThreads() && LoomSupport.isSupported()) {
            // without this every servlet invocation runs on the XNIO worker pool, eight threads per core by default,
            // and enable-virtual-threads was silently ignored: a blocking controller capped out at that pool's size
            deploymentInfo.setExecutor(Executors.newThreadPerTaskExecutor(
                LoomSupport.newVirtualThreadFactory("undertow-handler-", builder -> { })
            ));
        }
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
