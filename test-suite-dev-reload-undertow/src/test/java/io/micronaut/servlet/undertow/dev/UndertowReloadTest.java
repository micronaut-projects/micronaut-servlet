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
import io.micronaut.dev.tck.ReloadHarness;
import io.micronaut.dev.tck.ReloadTck;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.servlet.undertow.UndertowServer;
import io.undertow.Undertow;
import io.undertow.servlet.Servlets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two generations of an Undertow application in development mode: the server, its port and its thread pool are kept, and
 * each request is served by the handlers of the running generation, Micronaut's servlet and the servlets mounted beside
 * it alike.
 */
class UndertowReloadTest {

    private static final String KEPT_SERVER = "io.micronaut.servlet.undertow.RetainedUndertowServer";

    /**
     * A bean of the next generation that takes its time to start, so that requests wait for it.
     */
    private static final String SLOW_START = """
        package example;

        import io.micronaut.context.annotation.Context;

        @Context
        public class SlowStart {
            public SlowStart() throws InterruptedException {
                Thread.sleep(%d);
            }
        }
        """;

    private static final String OTHER_PORT_CONTROLLER = """
        package example;

        import io.micronaut.http.annotation.Controller;
        import io.micronaut.http.annotation.Get;
        import io.micronaut.http.annotation.Produces;
        import io.micronaut.http.MediaType;

        @Controller(value = "/other", port = "${other.port}")
        public class OtherController {
            @Get
            @Produces(MediaType.TEXT_PLAIN)
            public String other() {
                return "other";
            }
        }
        """;

    @TempDir
    Path project;

    @Test
    void aRestartKeepsTheServerAndTheChangedControllerAndServletAnswerOnIt() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("first"));
            harness.source("example.PlainServlet", UndertowApp.PLAIN_SERVLET.formatted("first"));
            harness.start();
            assertEquals("first", UndertowApp.get(port, "/hello"));
            assertEquals("plain first", UndertowApp.get(port, "/plain/x"));

            ApplicationContext first = harness.context();
            assertOneServer(first);
            Undertow server = first.getBean(UndertowServer.class).getServer();
            Object kept = bean(first, KEPT_SERVER);
            first = null;

            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("second"));
            harness.source("example.PlainServlet", UndertowApp.PLAIN_SERVLET.formatted("second"));
            harness.reload();
            assertEquals(2, harness.generation());

            ReloadTck.assertRetained(harness, kept);
            // the first generation's deployment left Undertow's static container as it stopped
            assertEquals(1, Servlets.defaultContainer().listDeployments().size(), "deployments " + Servlets.defaultContainer().listDeployments());
            ApplicationContext second = harness.context();
            assertOneServer(second);
            UndertowServer secondServer = second.getBean(UndertowServer.class);
            assertSame(server, secondServer.getServer(), "the Undertow server is the first generation's");
            assertEquals(port, secondServer.getPort());
            assertTrue(secondServer.isRunning());
            assertTrue(running(server));
            // the changed controller, and the servlet that is not Micronaut's, answer on the same server
            assertEquals("second", UndertowApp.get(port, "/hello"));
            assertEquals("plain second", UndertowApp.get(port, "/plain/x"));
            second = null;
            secondServer = null;
            server = null;
            kept = null;

            ReloadTck.assertRetiredGenerationsCollected(harness);
        }
    }

    @Test
    void aServletMountedInTheNextGenerationIsServedAndOneRemovedIsNot() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("first"));
            harness.start();
            String absent = UndertowApp.get(port, "/plain/x");
            assertTrue(absent.startsWith("404"), absent);

            // the next generation mounts a servlet: its mapping is registered in the next generation's servlet context
            harness.source("example.PlainServlet", UndertowApp.PLAIN_SERVLET.formatted("added"));
            harness.reload();
            assertEquals("plain added", UndertowApp.get(port, "/plain/x"));

            // the class stops being a servlet rather than goes: a deleted source can outlive its deletion by a batch
            harness.source("example.PlainServlet", "package example; public class PlainServlet { }");
            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("third"));
            harness.reload();
            assertEquals("third", UndertowApp.get(port, "/hello"));
            String removed = UndertowApp.get(port, "/plain/x");
            assertTrue(removed.startsWith("404"), removed);

            ReloadTck.assertRetiredGenerationsCollected(harness);
        }
    }

    @Test
    void aRequestInFlightFinishesOnTheStoppingGeneration() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            harness.source("example.SlowController", UndertowApp.SLOW_CONTROLLER.formatted(1500, "slow first"));
            harness.start();
            CompletableFuture<String> inFlight = CompletableFuture.supplyAsync(() -> UndertowApp.get(port, "/slow"));
            Thread.sleep(300);

            harness.source("example.SlowController", UndertowApp.SLOW_CONTROLLER.formatted(0, "slow second"));
            harness.reload();
            assertEquals("slow first", inFlight.get(30, TimeUnit.SECONDS), "drained on the generation it arrived on");
            assertEquals("slow second", UndertowApp.get(port, "/slow"));
        }
    }

    @Test
    void requestsWaitForASlowNextGenerationAndAreAnsweredWithA503PastTheHold() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            // the launcher's setting, which its request admission carries
            harness.manifest("requests.hold-timeout", "500ms");
            int port = UndertowApp.properties(harness, Map.of());
            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("first"));
            harness.source("example.PlainServlet", UndertowApp.PLAIN_SERVLET.formatted("first"));
            harness.start();
            assertEquals("first", UndertowApp.get(port, "/hello"));

            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("second"));
            harness.source("example.SlowStart", SLOW_START.formatted(2500));
            CompletableFuture<Void> reload = CompletableFuture.runAsync(harness::reload);
            // the next generation takes 2.5 s to start: past the hold, a request to any mapping is told to retry
            HttpResponse<String> unavailable = awaitUnavailable(port, "/plain/x");
            assertEquals(503, unavailable.statusCode());
            assertEquals("1", unavailable.headers().firstValue("Retry-After").orElse(null));
            reload.get(60, TimeUnit.SECONDS);
            assertEquals("second", UndertowApp.get(port, "/hello"));
        }
    }

    @Test
    void aPlainServletRequestMadeWhileABatchCompilesIsHeldUntilTheBatchIsAdmitted() throws Exception {
        Path gate = project.resolve("compile-gate");
        Path entered = project.resolve("compile-gate.entered");
        System.setProperty(CompileGate.GATE, gate.toString());
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            harness.source("example.PlainServlet", UndertowApp.PLAIN_SERVLET.formatted("first"));
            harness.start();
            assertEquals("plain first", UndertowApp.get(port, "/plain/x"));

            // the next compilation holds until the gate file is deleted: the batch is in its compile phase, and the
            // first generation still runs
            Files.writeString(gate, "");
            harness.source("example.PlainServlet", UndertowApp.PLAIN_SERVLET.formatted("second"));
            CompletableFuture<Void> reload = CompletableFuture.runAsync(harness::reload);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!Files.exists(entered)) {
                assertTrue(System.nanoTime() < deadline, "the compilation did not start");
                Thread.sleep(10);
            }
            CompletableFuture<String> held = CompletableFuture.supplyAsync(() -> UndertowApp.get(port, "/plain/x"));
            Thread.sleep(500);
            assertFalse(held.isDone(), "answered while the batch compiles: " + (held.isDone() ? held.join() : ""));

            Files.delete(gate);
            reload.get(60, TimeUnit.SECONDS);
            // admitted as the restart is about to stop the first generation, which serves it before it stops
            assertEquals("plain first", held.get(30, TimeUnit.SECONDS));
            assertEquals("plain second", UndertowApp.get(port, "/plain/x"));
        } finally {
            System.clearProperty(CompileGate.GATE);
        }
    }

    @Test
    void aChangeUnderTheServerPrefixReleasesTheServer() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("first"));
            harness.start();
            Undertow[] first = {harness.context().getBean(UndertowServer.class).getServer()};

            // the configuration changes, together with a class, so the application restarts
            harness.resource("application.properties", UndertowApp.propertiesText(port, Map.of("micronaut.server.idle-timeout", "61s")));
            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("second"));
            harness.reload();

            Undertow second = harness.context().getBean(UndertowServer.class).getServer();
            assertNotSame(first[0], second);
            assertFalse(running(first[0]), "the released server is stopped");
            assertEquals(port, harness.context().getBean(UndertowServer.class).getPort());
            assertEquals("second", UndertowApp.get(port, "/hello"));
            first[0] = null;
            second = null;

            ReloadTck.assertRetiredGenerationsCollected(harness);
        }
    }

    @Test
    void aGenerationWhoseRoutesExposeAnotherPortGetsAServerListeningOnIt() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int other = io.micronaut.core.io.socket.SocketUtils.findAvailableTcpPort();
            int port = UndertowApp.properties(harness, Map.of("other.port", String.valueOf(other)));
            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("first"));
            harness.start();
            Undertow[] first = {harness.context().getBean(UndertowServer.class).getServer()};

            // the generation's server has a connector for the exposed port: it is kept in place of the first one
            harness.source("example.OtherController", OTHER_PORT_CONTROLLER);
            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("second"));
            harness.reload();
            Undertow second = harness.context().getBean(UndertowServer.class).getServer();
            assertNotSame(first[0], second);
            assertFalse(running(first[0]), "the kept server is stopped");
            assertEquals("second", UndertowApp.get(port, "/hello"));
            assertEquals("other", UndertowApp.get(other, "/other"));
            assertOneServer(harness.context());
            first[0] = null;

            // without it, the next generation's server, listening on the first port only, is kept in its place (the
            // controller changes rather than goes: a deleted source can outlive its deletion by a batch)
            harness.source("example.OtherController", OTHER_PORT_CONTROLLER.replace(", port = \"${other.port}\"", ""));
            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("third"));
            harness.reload();
            assertFalse(running(second), "the server listening on the exposed port is stopped");
            assertEquals("other", UndertowApp.get(port, "/other"));
            second = null;
            assertEquals("third", UndertowApp.get(port, "/hello"));
            Object kept = bean(harness.context(), KEPT_SERVER);
            Undertow[] third = {harness.context().getBean(UndertowServer.class).getServer()};
            harness.source("example.HelloController", UndertowApp.CONTROLLER.formatted("fourth"));
            harness.reload();
            ReloadTck.assertRetained(harness, kept);
            assertSame(third[0], harness.context().getBean(UndertowServer.class).getServer());
            kept = null;
            third[0] = null;
            assertEquals("fourth", UndertowApp.get(port, "/hello"));

            ReloadTck.assertRetiredGenerationsCollected(harness);
        }
    }

    /**
     * Each generation deploys its own context, with its own session manager: a session of the stopping generation is
     * measured in the next one, through the session cookie.
     */
    @Test
    void aWebSocketOfTheStoppingGenerationIsClosedGoingAwayAndTheNextServesANewOne() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            harness.source("example.EchoEndpoint", UndertowApp.WEBSOCKET_ENDPOINT.formatted("first"));
            harness.start();
            LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
            WebSocket socket = connect(port, events);
            socket.sendText("a", true);
            assertEquals("text first a", events.poll(10, TimeUnit.SECONDS));

            harness.source("example.EchoEndpoint", UndertowApp.WEBSOCKET_ENDPOINT.formatted("second"));
            harness.reload();
            String closed = events.poll(10, TimeUnit.SECONDS);
            assertTrue(closed != null && closed.startsWith("close 1001"), "closed going away: " + closed);

            LinkedBlockingQueue<String> next = new LinkedBlockingQueue<>();
            WebSocket again = connect(port, next);
            again.sendText("b", true);
            assertEquals("text second b", next.poll(10, TimeUnit.SECONDS));
            again.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
            socket = null;
            again = null;

            ReloadTck.assertRetiredGenerationsCollected(harness);
        }
    }

    private static HttpResponse<String> awaitUnavailable(int port, String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 503) {
                return response;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No request was answered with a 503");
    }

    private static WebSocket connect(int port, LinkedBlockingQueue<String> events) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + port + "/echo"), new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                events.add("text " + data);
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                events.add("close " + statusCode + " " + reason);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                events.add("error " + error);
            }
        }).get(10, TimeUnit.SECONDS);
    }

    /**
     * In development mode the development server and the server built by the development factory replace the
     * production beans: there is one of each.
     */
    private static void assertOneServer(ApplicationContext context) {
        assertEquals(1, context.getBeansOfType(EmbeddedServer.class).size(), "one embedded server");
        assertEquals(1, context.getBeansOfType(Undertow.class).size(), "one Undertow server");
        assertEquals("io.micronaut.servlet.undertow.DevelopmentUndertowServer", context.getBean(EmbeddedServer.class).getClass().getName());
    }

    private static boolean running(Undertow undertow) {
        return undertow.getWorker() != null;
    }

    private static Object bean(ApplicationContext context, String className) {
        try {
            return context.getBean(Class.forName(className, true, context.getClassLoader()));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(className + " is not on the classpath", e);
        }
    }
}
