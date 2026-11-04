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

import io.micronaut.dev.tck.ReloadHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Restarts an Undertow application several times while clients call a controller and a servlet that is not Micronaut's
 * without pause, and reports how long each restart took, how long a call waited at most, and how many calls failed.
 * Every call succeeds: the server is retained, and the calls that arrive while one generation stops and the next
 * starts wait for the next.
 */
class UndertowRestartTimingTest {

    private static final int RESTARTS = 5;

    @TempDir
    Path project;

    @Test
    void requestsDuringRestartsWaitForTheNextGeneration() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("v0"));
            harness.source("example.PlainServlet", UndertowApp.PLAIN_SERVLET.formatted("v0"));
            harness.start();
            assertEquals("v0", UndertowApp.get(port, "/hello"));
            assertEquals("plain v0", UndertowApp.get(port, "/plain/x"));

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicInteger calls = new AtomicInteger();
            List<String> failures = Collections.synchronizedList(new ArrayList<>());
            AtomicLong maxLatency = new AtomicLong();
            List<Thread> clients = new ArrayList<>();
            for (String path : List.of("/hello", "/plain/x")) {
                Thread client = new Thread(() -> {
                    while (running.get()) {
                        long start = System.nanoTime();
                        String answer = UndertowApp.get(port, path);
                        if (answer.startsWith("v") || answer.startsWith("plain v")) {
                            calls.incrementAndGet();
                        } else {
                            failures.add(path + " -> " + answer);
                        }
                        maxLatency.accumulateAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), Math::max);
                    }
                }, "undertow-timing-client" + path);
                client.start();
                clients.add(client);
            }
            try {
                long[] reloads = new long[RESTARTS];
                long[] firstAnswers = new long[RESTARTS];
                for (int i = 1; i <= RESTARTS; i++) {
                    harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("v" + i));
                    harness.source("example.PlainServlet", UndertowApp.PLAIN_SERVLET.formatted("v" + i));
                    long start = System.nanoTime();
                    harness.reload();
                    reloads[i - 1] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    String expected = "v" + i;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    while (!expected.equals(UndertowApp.get(port, "/hello")) || !("plain " + expected).equals(UndertowApp.get(port, "/plain/x"))) {
                        if (System.nanoTime() > deadline) {
                            throw new AssertionError("Generation " + (i + 1) + " never answered");
                        }
                        Thread.sleep(1);
                    }
                    firstAnswers[i - 1] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                }
                running.set(false);
                for (Thread client : clients) {
                    client.join();
                }
                System.out.printf("Undertow restarts: reload ms %s, first answer of the new generation ms %s, %d calls, max call latency %d ms, %d failure(s) %s%n",
                    Arrays.toString(reloads), Arrays.toString(firstAnswers), calls.get(), maxLatency.get(), failures.size(),
                    failures.stream().map(f -> f.replaceAll("v\\d", "vN")).distinct().toList());
                assertEquals(List.of(), failures, "no call fails across the restarts");
            } finally {
                running.set(false);
                for (Thread client : clients) {
                    client.join();
                }
            }
        }
    }
}
