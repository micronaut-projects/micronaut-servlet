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
package io.micronaut.servlet.http.server.jdk;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request executor belongs to the server that created it and must not outlive it.
 *
 * <p>{@link HttpServer#stop(int)} leaves the executor running, and the fallback pool's workers are not daemon
 * threads, so a stopped server would otherwise leak them.</p>
 */
class ExecutorLifecycleTest {

    @Test
    void theExecutorIsShutDownWhenTheServerStops() {
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "ExecutorLifecycleTest",
            "micronaut.servlet.enable-virtual-threads", "false"
        ));
        HttpServer httpServer = (HttpServer) server.getApplicationContext().getBean(HttpServer.class);
        ExecutorService executor = assertInstanceOf(ExecutorService.class, httpServer.getExecutor(),
            "the JDK server must be given a real executor, not left on its dispatcher thread");
        assertFalse(executor.isShutdown(), "the executor must be live while the server is running");

        server.stop();

        assertTrue(executor.isShutdown(), "the executor must be shut down with the server");
    }

    @Test
    void theFallbackPoolBoundsItsQueue() {
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "ExecutorLifecycleTest",
            "micronaut.servlet.enable-virtual-threads", "false"
        ));
        try {
            HttpServer httpServer = (HttpServer) server.getApplicationContext().getBean(HttpServer.class);
            ThreadPoolExecutor executor = assertInstanceOf(ThreadPoolExecutor.class, httpServer.getExecutor());

            // an unbounded queue lets exchanges accumulate until the heap is gone; remainingCapacity is
            // Integer.MAX_VALUE for the unbounded case
            assertTrue(executor.getQueue().remainingCapacity() < Integer.MAX_VALUE,
                "the request queue must be bounded");
            assertInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class, executor.getRejectedExecutionHandler(),
                "overflow must push back rather than be dropped");
        } finally {
            server.stop();
        }
    }
}
