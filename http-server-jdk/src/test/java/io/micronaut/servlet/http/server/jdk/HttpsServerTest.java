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
import com.sun.net.httpserver.HttpsServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With SSL enabled the built-in server listens for TLS on the SSL port, as the other runtimes do.
 */
class HttpsServerTest {

    private static final String KEY_STORE = "jdk-server-test.p12";
    private static final String PASSWORD = "changeit";

    @Test
    void theServerSpeaksTlsWhenSslIsEnabled() throws Exception {
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "HttpsServerTest",
            "micronaut.ssl.enabled", "true",
            "micronaut.server.ssl.build-self-signed", "false",
            "micronaut.ssl.key-store.path", "classpath:" + KEY_STORE,
            "micronaut.ssl.key-store.type", "PKCS12",
            "micronaut.ssl.key-store.password", PASSWORD
        ));
        try {
            assertInstanceOf(HttpsServer.class, server.getApplicationContext().getBean(HttpServer.class));
            assertEquals("https", server.getScheme());
            assertTrue(server.getURI().toString().startsWith("https://"), server.getURI().toString());

            try (HttpClient client = HttpClient.newBuilder().sslContext(trustingTheServer()).build()) {
                HttpResponse<String> response = client.send(
                    java.net.http.HttpRequest.newBuilder(server.getURI().resolve("/tls/secure")).build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, response.statusCode());
                assertEquals("true", response.body(), "the request must be seen as secure");
            }
        } finally {
            server.close();
        }
    }

    private static SSLContext trustingTheServer() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = HttpsServerTest.class.getClassLoader().getResourceAsStream(KEY_STORE)) {
            trustStore.load(in, PASSWORD.toCharArray());
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }

    @Requires(property = "spec.name", value = "HttpsServerTest")
    @Controller("/tls")
    @Secured(SecurityRule.IS_ANONYMOUS)
    static class TlsController {

        @Get("/secure")
        String secure(HttpRequest<?> request) {
            return String.valueOf(request.isSecure());
        }
    }
}
