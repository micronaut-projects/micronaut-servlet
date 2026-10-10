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
package io.micronaut.servlet.tomcat.dev;

import io.micronaut.dev.livereload.LiveReloadServer;
import io.micronaut.dev.tck.ReloadHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The LiveReload script in the HTML pages of a Tomcat application in development mode: a page a plain servlet writes
 * gets it as a Micronaut page does, a Micronaut page and a static page get it once, anything else is left alone.
 */
class TomcatLiveReloadTest {

    @TempDir
    Path project;

    @Test
    void htmlPagesGetTheScriptOnceAndOtherResponsesAreUntouched() throws Exception {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            int port = TomcatApp.properties(harness, Map.of(
                "micronaut.router.static-resources.site.paths", "classpath:site",
                "micronaut.router.static-resources.site.mapping", "/site/**"
            ));
            harness.manifest("micronaut.dev.livereload.port", "0");
            TomcatApp.liveReloadSources(harness);
            harness.start();
            int liveReloadPort = harness.runtime().liveReload().map(LiveReloadServer::port).orElseThrow();
            String expected = TomcatApp.PAGE.replace("</body>", LiveReloadServer.scriptTag(liveReloadPort) + "</body>");
            HttpClient client = HttpClient.newHttpClient();

            for (String path : new String[] {"/plain-writer", "/plain-stream", "/page", "/site/index.html"}) {
                HttpResponse<byte[]> response = get(client, port, path);
                assertEquals(200, response.statusCode(), path);
                assertEquals(expected, new String(response.body(), StandardCharsets.UTF_8), path);
                // the length served is that of the page with the script
                response.headers().firstValue("content-length").ifPresent(length ->
                    assertEquals(response.body().length, Integer.parseInt(length), path));
            }

            for (String path : new String[] {"/plain-json", "/site/notes.txt"}) {
                HttpResponse<byte[]> response = get(client, port, path);
                assertEquals(200, response.statusCode(), path);
                String body = new String(response.body(), StandardCharsets.UTF_8);
                assertFalse(body.contains("livereload.js"), path + ": " + body);
                assertEquals(TomcatApp.NOT_HTML, body, path);
            }

            // a page flushed before its closing body tag is streamed: it goes out as written
            HttpResponse<byte[]> streamed = get(client, port, "/plain-streamed");
            assertEquals(TomcatApp.PAGE, new String(streamed.body(), StandardCharsets.UTF_8));

            // a policy set after the page was written, a partial response and an asynchronous request the servlet
            // dispatches: sent as written
            for (String path : new String[] {"/plain-late-csp", "/plain-partial", "/plain-async-dispatch"}) {
                HttpResponse<byte[]> response = get(client, port, path);
                assertEquals(TomcatApp.PAGE, new String(response.body(), StandardCharsets.UTF_8), path);
            }
        }
    }

    private static HttpResponse<byte[]> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
