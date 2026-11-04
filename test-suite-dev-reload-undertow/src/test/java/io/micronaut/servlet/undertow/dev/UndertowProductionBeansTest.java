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
package io.micronaut.servlet.undertow.dev;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.servlet.undertow.UndertowServer;
import io.undertow.Undertow;
import io.micronaut.servlet.http.server.DevelopmentSessionStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Outside development mode the production beans run, and none of the development ones exists.
 */
class UndertowProductionBeansTest {

    @Test
    void outsideDevelopmentModeTheProductionServerRuns() throws Exception {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("micronaut.server.port", -1))) {
            ApplicationContext context = server.getApplicationContext();
            assertEquals(UndertowServer.class, server.getClass());
            assertEquals(1, context.getBeansOfType(EmbeddedServer.class).size());
            assertEquals(1, context.getBeansOfType(Undertow.class).size());
            assertFalse(context.containsBean(Class.forName("io.micronaut.servlet.undertow.RetainedUndertowServer")));
            assertFalse(context.containsBean(DevelopmentSessionStore.class));
        }
    }
}
