/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.servlet.jetty;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.core.annotation.Internal;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletContainerInitializer;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

/**
 * Builds the Jetty server of a generation of the application in development mode, in place of
 * {@link JettyFactory#jettyServer(ApplicationContext, MicronautServletConfiguration, JettyConfiguration.JettySslConfiguration, Collection, RequestLog)}:
 * the server is built by the {@link JettyFactory}, with the loader of Jetty's integration as the context class loader.
 * What the server creates keeps the context class loader of the creating thread, as its scheduler, which starts its
 * thread with it, and the server may be kept across restarts by {@link RetainedJettyServer}, where a generation's
 * loader would keep that generation.
 *
 * <p>Not a subclass of {@link JettyFactory}: a subclass of a factory produces every bean of the factory again, its
 * request logs included.</p>
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Factory
@Requires(beans = JettyFactory.class)
@DevelopmentActive
final class DevelopmentJettyFactory {

    /**
     * The Jetty server of the generation, built by the {@link JettyFactory}.
     *
     * @param factory The factory
     * @param applicationContext This application context
     * @param configuration The servlet configuration
     * @param jettySslConfiguration The Jetty SSL configuration
     * @param servletContainerInitializers The servlet container initializers
     * @param requestLog The access log
     * @return The Jetty server, not started
     * @throws Exception If the server cannot be built
     */
    @Singleton
    @Primary
    @Replaces(bean = Server.class, factory = JettyFactory.class)
    Server developmentJettyServer(JettyFactory factory,
                                  ApplicationContext applicationContext,
                                  MicronautServletConfiguration configuration,
                                  JettyConfiguration.JettySslConfiguration jettySslConfiguration,
                                  Collection<ServletContainerInitializer> servletContainerInitializers,
                                  @Nullable RequestLog requestLog) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        thread.setContextClassLoader(DevelopmentJettyFactory.class.getClassLoader());
        try {
            return factory.jettyServer(applicationContext, configuration, jettySslConfiguration, servletContainerInitializers, requestLog);
        } finally {
            thread.setContextClassLoader(loader);
        }
    }
}
