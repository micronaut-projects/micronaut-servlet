package io.micronaut.servlet.websocket;

import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.cors.CorsOriginConfiguration;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Extension;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicronautEndpointConfiguratorTest {

    private static final String SAME_ORIGIN = "https://app.example";

    private MicronautEndpointConfigurator configurator(boolean corsEnabled,
                                                       List<String> allowedOrigins,
                                                       String allowedOriginsRegex) {
        HttpServerConfiguration.CorsConfiguration cors = new HttpServerConfiguration.CorsConfiguration();
        cors.setEnabled(corsEnabled);
        CorsOriginConfiguration origin = new CorsOriginConfiguration();
        if (allowedOrigins != null) {
            origin.setAllowedOrigins(allowedOrigins);
        }
        if (allowedOriginsRegex != null) {
            origin.setAllowedOriginsRegex(allowedOriginsRegex);
        }
        cors.setConfigurations(Map.of("web", origin));
        return new MicronautEndpointConfigurator(null, true, Map.of(), cors, SAME_ORIGIN);
    }

    @Test
    void withCorsDisabledEveryOriginIsAccepted() {
        MicronautEndpointConfigurator configurator = configurator(false, List.of("https://trusted.example"), null);

        assertTrue(configurator.checkOrigin("https://evil.example"));
    }

    @Test
    void withCorsEnabledOnlyConfiguredOriginsAreAccepted() {
        MicronautEndpointConfigurator configurator = configurator(true, List.of("https://trusted.example"), null);

        assertTrue(configurator.checkOrigin("https://trusted.example"));
        assertFalse(configurator.checkOrigin("https://evil.example"));
    }

    @Test
    void aSameOriginHandshakeIsAlwaysAccepted() {
        MicronautEndpointConfigurator configurator = configurator(true, List.of("https://trusted.example"), null);

        assertTrue(configurator.checkOrigin(SAME_ORIGIN), "a browser sends Origin even for a same-origin socket");
    }

    @Test
    void aHandshakeWithoutAnOriginIsNotBrowserInitiatedAndIsAccepted() {
        MicronautEndpointConfigurator configurator = configurator(true, List.of("https://trusted.example"), null);

        assertTrue(configurator.checkOrigin(null));
        assertTrue(configurator.checkOrigin(""));
    }

    @Test
    void aWildcardAllowsEveryOrigin() {
        MicronautEndpointConfigurator configurator = configurator(true, CorsOriginConfiguration.ANY, null);

        assertTrue(configurator.checkOrigin("https://evil.example"));
    }

    @Test
    void anOriginRegexIsHonoured() {
        MicronautEndpointConfigurator configurator = configurator(true, List.of(), "^https://.*\\.trusted\\.example$");

        assertTrue(configurator.checkOrigin("https://a.trusted.example"));
        assertFalse(configurator.checkOrigin("https://evil.example"));
    }

    @Test
    void anInvalidOriginRegexRejectsRatherThanFailing() {
        MicronautEndpointConfigurator configurator = configurator(true, List.of(), "([");

        assertFalse(configurator.checkOrigin("https://evil.example"));
    }

    @Test
    void compressionCanBeTurnedOff() {
        List<Extension> installed = List.of();
        MicronautEndpointConfigurator disabled =
            new MicronautEndpointConfigurator(null, false, Map.of(), new HttpServerConfiguration.CorsConfiguration(), null);

        assertTrue(disabled.getNegotiatedExtensions(installed, installed).isEmpty(),
            "no extension is negotiated when per-message compression is turned off");
    }

    @Test
    void filterHeadersAreCopiedOntoTheHandshakeButReservedOnesAreNot() {
        Map<String, List<String>> filterHeaders = new HashMap<>();
        filterHeaders.put("Set-Cookie", List.of("SESSION=abc"));
        filterHeaders.put("Upgrade", List.of("nonsense"));
        filterHeaders.put("Sec-WebSocket-Accept", List.of("nonsense"));
        MicronautEndpointConfigurator configurator = new MicronautEndpointConfigurator(
            null, true, filterHeaders, new HttpServerConfiguration.CorsConfiguration(), null);
        TestHandshakeResponse response = new TestHandshakeResponse();

        configurator.modifyHandshake(new TestServerEndpointConfig(), new TestHandshakeRequest(), response);

        assertEquals(List.of("SESSION=abc"), response.headers.get("Set-Cookie"));
        assertFalse(response.headers.containsKey("Upgrade"), "the container owns the handshake headers");
        assertFalse(response.headers.containsKey("Sec-WebSocket-Accept"));
    }

    @Test
    void theEndpointInstanceIsTheMicronautEndpoint() throws Exception {
        MicronautEndpointConfigurator configurator = configurator(false, List.of(), null);

        Endpoint endpoint = configurator.getEndpointInstance(MicronautServerEndpoint.class);

        assertInstanceOf(MicronautServerEndpoint.class, endpoint);
    }

    private static final class TestServerEndpointConfig implements ServerEndpointConfig {
        private final Map<String, Object> userProperties = new HashMap<>();

        @Override
        public Class<?> getEndpointClass() {
            return MicronautServerEndpoint.class;
        }

        @Override
        public String getPath() {
            return "/chat";
        }

        @Override
        public List<String> getSubprotocols() {
            return List.of();
        }

        @Override
        public List<Extension> getExtensions() {
            return List.of();
        }

        @Override
        public Configurator getConfigurator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Class<? extends jakarta.websocket.Encoder>> getEncoders() {
            return List.of();
        }

        @Override
        public List<Class<? extends jakarta.websocket.Decoder>> getDecoders() {
            return List.of();
        }

        @Override
        public Map<String, Object> getUserProperties() {
            return userProperties;
        }
    }

    private static final class TestHandshakeResponse implements HandshakeResponse {
        private final Map<String, List<String>> headers = new HashMap<>();

        @Override
        public Map<String, List<String>> getHeaders() {
            return headers;
        }
    }

    private static final class TestHandshakeRequest implements HandshakeRequest {
        @Override
        public Map<String, List<String>> getHeaders() {
            return Map.of();
        }

        @Override
        public Principal getUserPrincipal() {
            return null;
        }

        @Override
        public URI getRequestURI() {
            return URI.create("/chat");
        }

        @Override
        public boolean isUserInRole(String role) {
            return false;
        }

        @Override
        public Object getHttpSession() {
            return null;
        }

        @Override
        public Map<String, List<String>> getParameterMap() {
            return Map.of();
        }

        @Override
        public String getQueryString() {
            return "";
        }
    }
}
