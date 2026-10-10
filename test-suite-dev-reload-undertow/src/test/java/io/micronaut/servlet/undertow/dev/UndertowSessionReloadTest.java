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
import io.micronaut.dev.tck.ReloadTck;
import io.micronaut.servlet.http.server.DevelopmentSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The container sessions of an Undertow application in development mode, kept across a restart: the next generation
 * restores them under the same ids, on their first request, with the attributes that serialize and deserialize, read
 * with its own classes.
 */
class UndertowSessionReloadTest {

    @TempDir
    Path project;

    @Test
    void aSessionAttributeSetInOneGenerationIsReadInTheNextWithTheSameCookie() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            sources(harness, "first", 1);
            harness.start();
            HttpClient client = client();
            HttpClient idle = client();
            assertEquals("first new value=kept cart=kept/current opaque=set counter=1", session(client, port, "?set=kept"));
            assertEquals("first new value=idle cart=idle/current opaque=set counter=1", session(idle, port, "?set=idle"));
            String id = session(client, port, "?id");

            sources(harness, "second", 1);
            harness.reload();
            // Undertow restores the session on its first request, as a new session under the same id, with the cart
            // deserialized with the new generation's class
            assertEquals("second new value=kept cart=kept/current opaque=null counter=1", session(client, port, ""));
            assertEquals("second existing value=kept cart=kept/current opaque=null counter=1", session(client, port, ""));

            // the same session, under the same id
            assertEquals(id, session(client, port, "?id"));

            sources(harness, "third", 1);
            harness.reload();
            assertEquals("third new value=kept cart=kept/current opaque=null counter=1", session(client, port, ""));
            // a session no request asked for in the second generation stays saved for the third
            assertEquals("third new value=idle cart=idle/current opaque=null counter=1", session(idle, port, ""));

            ReloadTck.assertRetiredGenerationsCollected(harness);
        }
    }

    @Test
    void anAttributeWhoseClassChangedIncompatiblyIsDroppedAndTheRestKept() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            sources(harness, "first", 1);
            harness.start();
            HttpClient client = client();
            assertEquals("first new value=kept cart=kept/current opaque=set counter=1", session(client, port, "?set=kept"));

            // a new serialVersionUID: the saved counter no longer deserializes
            sources(harness, "second", 2);
            harness.reload();
            assertEquals("second new value=kept cart=kept/current opaque=null counter=null", session(client, port, ""));
        }
    }

    @Test
    void anExpiredSessionIsNotRestored() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            sources(harness, "first", 1);
            harness.start();
            HttpClient client = client();
            assertEquals("first new value=short cart=short/current opaque=set counter=1", session(client, port, "?set=short&ttl=1"));
            Thread.sleep(2_100);

            sources(harness, "second", 1);
            harness.reload();
            assertEquals("second new value=null cart=null opaque=null counter=null", session(client, port, ""));
        }
    }

    @Test
    void aSessionKeepsItsMaximumInactiveIntervalAndOneThatNeverExpiresIsKept() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            sources(harness, "first", 1);
            harness.start();
            HttpClient forever = client();
            HttpClient hour = client();
            assertEquals("first new value=forever cart=forever/current opaque=set counter=1", session(forever, port, "?set=forever&ttl=0"));
            assertEquals("first new value=hour cart=hour/current opaque=set counter=1", session(hour, port, "?set=hour&ttl=3600"));

            sources(harness, "second", 1);
            harness.reload();
            assertEquals("second new value=forever cart=forever/current opaque=null counter=1", session(forever, port, ""));
            assertEquals("0", session(forever, port, "?interval"));
            assertEquals("second new value=hour cart=hour/current opaque=null counter=1", session(hour, port, ""));
            assertEquals("3600", session(hour, port, "?interval"));
        }
    }

    @Test
    void aChangeOfTheSwitchDiscardsTheSavedSessions() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of());
            sources(harness, "first", 1);
            harness.start();
            HttpClient client = client();
            assertEquals("first new value=kept cart=kept/current opaque=set counter=1", session(client, port, "?set=kept"));

            harness.resource("application.properties", UndertowApp.propertiesText(port, Map.of(DevelopmentSessionStore.PERSIST_PROPERTY, "false")));
            sources(harness, "second", 1);
            harness.reload();

            // turned on again: the session saved before it was turned off is gone
            harness.resource("application.properties", UndertowApp.propertiesText(port, Map.of(DevelopmentSessionStore.PERSIST_PROPERTY, "true")));
            sources(harness, "third", 1);
            harness.reload();
            assertEquals("third new value=null cart=null opaque=null counter=null", session(client, port, ""));
        }
    }

    @Test
    void sessionsAreNotKeptWhenTurnedOff() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = UndertowApp.properties(harness, Map.of("micronaut.servlet.dev.sessions.persist", "false"));
            sources(harness, "first", 1);
            harness.start();
            HttpClient client = client();
            assertEquals("first new value=kept cart=kept/current opaque=set counter=1", session(client, port, "?set=kept"));

            sources(harness, "second", 1);
            harness.reload();
            // the stopping generation's context takes its sessions with it: the next one starts a new session
            assertEquals("second new value=null cart=null opaque=null counter=null", session(client, port, ""));
        }
    }

    private static void sources(ReloadHarness harness, String generation, int counterVersion) {
        harness.source("example.SessionServlet", UndertowApp.SESSION_SERVLET.formatted(generation));
        harness.source("example.Cart", UndertowApp.SESSION_CART);
        harness.source("example.Counter", UndertowApp.SESSION_COUNTER.formatted(counterVersion));
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private static String session(HttpClient client, int port, String query) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/session" + query))
            .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : response.statusCode() + " " + response.body();
    }
}
