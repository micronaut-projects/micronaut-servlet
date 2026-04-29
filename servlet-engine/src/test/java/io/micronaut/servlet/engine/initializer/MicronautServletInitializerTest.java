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
package io.micronaut.servlet.engine.initializer;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.micronaut.servlet.engine.DefaultMicronautServlet.CONTEXT_ATTRIBUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MicronautServletInitializerTest {

    @Test
    void publishesServerLifecycleEventsForExternalServletContexts() {
        AtomicReference<ServletContextListener> shutdownListener = new AtomicReference<>();
        ServletContext servletContext = servletContext(shutdownListener);

        try (ApplicationContext applicationContext = ApplicationContext.builder()
            .properties(Map.of("spec.name", "MicronautServletInitializerTest"))
            .singletons(servletContext)
            .build()
            .start()) {
            ServerEventRecorder recorder = applicationContext.getBean(ServerEventRecorder.class);

            new MicronautServletInitializer(applicationContext).onStartup(Set.of(), servletContext);

            assertEquals(1, recorder.startupCount.get());
            assertSame(applicationContext, servletContext.getAttribute(CONTEXT_ATTRIBUTE));

            ServletContextListener listener = shutdownListener.get();
            assertNotNull(listener);

            listener.contextDestroyed(new ServletContextEvent(servletContext));

            assertEquals(1, recorder.shutdownCount.get());
            assertFalse(applicationContext.isRunning());
        }
    }

    private static ServletContext servletContext(AtomicReference<ServletContextListener> shutdownListener) {
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        return (ServletContext) Proxy.newProxyInstance(
            MicronautServletInitializerTest.class.getClassLoader(),
            new Class<?>[]{ServletContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "addFilter" -> registration(FilterRegistration.Dynamic.class);
                case "addListener" -> {
                    if (args[0] instanceof ServletContextListener listener) {
                        shutdownListener.set(listener);
                    }
                    yield null;
                }
                case "addServlet" -> registration(ServletRegistration.Dynamic.class);
                case "getAttribute" -> attributes.get(args[0]);
                case "getAttributeNames" -> Collections.enumeration(attributes.keySet());
                case "getClassLoader" -> MicronautServletInitializerTest.class.getClassLoader();
                case "getContextPath" -> "";
                case "setAttribute" -> {
                    attributes.put((String) args[0], args[1]);
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static <T> T registration(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
            MicronautServletInitializerTest.class.getClassLoader(),
            new Class<?>[]{type},
            (proxy, method, args) -> defaultValue(method.getReturnType())
        ));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (Set.class.isAssignableFrom(returnType)) {
            return Collections.emptySet();
        }
        return null;
    }

    @Singleton
    @Requires(property = "spec.name", value = "MicronautServletInitializerTest")
    static final class ServerEventRecorder {
        private final AtomicInteger startupCount = new AtomicInteger();
        private final AtomicInteger shutdownCount = new AtomicInteger();

        @EventListener
        void onStartup(ServerStartupEvent event) {
            startupCount.incrementAndGet();
        }

        @EventListener
        void onShutdown(ServerShutdownEvent event) {
            shutdownCount.incrementAndGet();
        }
    }
}
