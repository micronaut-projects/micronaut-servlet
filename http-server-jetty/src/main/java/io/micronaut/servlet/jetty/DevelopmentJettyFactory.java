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
import org.eclipse.jetty.server.handler.ContextHandler;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds the Jetty server of a generation of the application in development mode, in place of
 * {@link JettyFactory#jettyServer(ApplicationContext, MicronautServletConfiguration, JettyConfiguration.JettySslConfiguration, Collection, RequestLog)}:
 * the server is built by the {@link JettyFactory}, with a loader of Jetty's integration as the context class loader.
 * What the server creates keeps the context class loader of the creating thread, as its scheduler, which starts its
 * thread with it, and the server may be kept across restarts by {@link RetainedJettyServer}, where a generation's
 * loader would keep that generation. A resource looked up through that loader while the server is built, as a
 * {@code classpath:} path of Jetty's own static resources or of a key store is, is found with the generation's loader
 * as well, which the loader lets go of once the server is built. A context handler of the generation that took the
 * context class loader as it was created gets the one it would have had otherwise.
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
        ClassLoader library = DevelopmentJettyFactory.class.getClassLoader();
        BuildLoader build = new BuildLoader(library, loader);
        Server server;
        thread.setContextClassLoader(build);
        try {
            server = factory.jettyServer(applicationContext, configuration, jettySslConfiguration, servletContainerInitializers, requestLog);
        } finally {
            thread.setContextClassLoader(loader);
            build.release();
        }
        // the handlers are the generation's: a context handler that took the context class loader as it was created,
        // which makes it the context class loader of its requests, gets the one it would have had without the switch
        for (ContextHandler context : server.getDescendants(ContextHandler.class)) {
            if (context.getClassLoader() == build) {
                context.setClassLoader(loader);
            }
        }
        return server;
    }

    /**
     * The context class loader while the server is built: classes come from Jetty's integration, and a resource is
     * looked up with the generation's loader first, as the application's under a {@code classpath:} path are, until
     * the server is built. What keeps this loader past the build, as the server's scheduler does, keeps no
     * generation.
     */
    private static final class BuildLoader extends ClassLoader {

        private volatile @Nullable ClassLoader generation;

        BuildLoader(ClassLoader library, @Nullable ClassLoader generation) {
            super(library);
            this.generation = generation;
        }

        void release() {
            generation = null;
        }

        @Override
        public @Nullable URL getResource(String name) {
            ClassLoader loader = generation;
            URL url = loader != null ? loader.getResource(name) : null;
            return url != null ? url : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            ClassLoader loader = generation;
            if (loader == null || loader == getParent()) {
                return super.getResources(name);
            }
            // the generation's first, as a lookup through it alone would order them, then the library's it lacks
            Set<URL> urls = new LinkedHashSet<>(Collections.list(loader.getResources(name)));
            urls.addAll(Collections.list(super.getResources(name)));
            return Collections.enumeration(urls);
        }
    }
}
