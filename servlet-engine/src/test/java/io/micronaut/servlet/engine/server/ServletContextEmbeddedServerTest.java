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
package io.micronaut.servlet.engine.server;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.ApplicationConfiguration;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServletContextEmbeddedServerTest {

    @Test
    void resolvesConfiguredServerMetadataAndLifecycleState() throws Exception {
        ServletContext servletContext = servletContext("/demo");

        try (ApplicationContext applicationContext = ApplicationContext.builder()
            .singletons(servletContext)
            .properties(Map.of(
                "micronaut.server.host", "example.com",
                "micronaut.server.port", 8443,
                "micronaut.ssl.enabled", true
            ))
            .build()
            .start()) {
            ServletContextEmbeddedServer embeddedServer = applicationContext.getBean(ServletContextEmbeddedServer.class);

            assertSame(applicationContext, embeddedServer.getApplicationContext());
            assertSame(applicationContext.getBean(ApplicationConfiguration.class), embeddedServer.getApplicationConfiguration());
            assertEquals("example.com", embeddedServer.getHost());
            assertEquals(8443, embeddedServer.getPort());
            assertEquals("https", embeddedServer.getScheme());
            assertEquals(new URI("https://example.com:8443/demo"), embeddedServer.getURI());
            assertEquals(new URL("https://example.com:8443/demo"), embeddedServer.getURL());
            assertTrue(embeddedServer.isRunning());

            embeddedServer.stop();
            assertFalse(embeddedServer.isRunning());

            embeddedServer.start();
            assertTrue(embeddedServer.isRunning());

            applicationContext.stop();
            assertFalse(embeddedServer.isRunning());
        }
    }

    @Test
    void usesDefaultHostSchemeAndPortWhenNotConfigured() {
        try (ApplicationContext applicationContext = ApplicationContext.builder()
            .singletons(servletContext(""))
            .build()
            .start()) {
            ServletContextEmbeddedServer embeddedServer = applicationContext.getBean(ServletContextEmbeddedServer.class);

            assertEquals("localhost", embeddedServer.getHost());
            assertEquals(-1, embeddedServer.getPort());
            assertEquals("http", embeddedServer.getScheme());
            assertEquals(URI.create("http://localhost"), embeddedServer.getURI());
            assertNotNull(embeddedServer.getURL());
        }
    }

    private static ServletContext servletContext(String contextPath) {
        return (ServletContext) Proxy.newProxyInstance(
            ServletContextEmbeddedServerTest.class.getClassLoader(),
            new Class<?>[]{ServletContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getClassLoader" -> ServletContextEmbeddedServerTest.class.getClassLoader();
                case "getContextPath" -> contextPath;
                default -> defaultValue(method.getReturnType());
            }
        );
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
        return null;
    }
}
