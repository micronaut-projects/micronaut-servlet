package io.micronaut.servlet.http.server.jdk.websocket;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.spi.HttpServerProvider;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.servlet.http.server.jdk.JdkHttpServerConfiguration.WebSocketUpgrade;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HttpServer provider of the JDK cannot switch protocols: a WebSocket handshake is refused with
 * a 501, and the application is told at startup.
 */
class WebSocketUnsupportedTest {

    private static final String SPEC = "WebSocketUnsupportedTest";

    @Test
    void theHandshakeIsRefusedAndStartupWarns() throws IOException, InterruptedException {
        assertFalse(JdkWebSocketCapability.isUpgradingProvider(HttpServerProvider.provider().getClass().getName()),
            "the tests of this module run on the provider of the JDK");
        Logger logger = (Logger) LoggerFactory.getLogger(JdkWebSocketStartupCheck.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", SPEC, "micronaut.security.enabled", false))) {
            List<String> warnings = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(1, warnings.size(), warnings.toString());
            String warning = warnings.getFirst();
            assertTrue(warning.contains("WebSocket"), warning);
            assertTrue(warning.contains("Jetty, Tomcat, Undertow or Netty"), warning);
            assertTrue(warning.contains("io.github.robaho:httpserver"), warning);

            String response = handshake(server.getPort(), "/unsupported/chat");
            assertTrue(response.startsWith("HTTP/1.1 501"), response);

            // the server goes on serving: the connection of the handshake was not taken over
            HttpResponse<String> plain = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + "/unsupported/chat")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, plain.statusCode());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void noWarningWithoutWebSocketEndpoints() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", SPEC + "-none"))) {
            assertNull(context.getBean(JdkWebSocketStartupCheck.class).problem());
        }
    }

    @Test
    void theUpgradeFollowsTheSetting() {
        String jdk = "sun.net.httpserver.DefaultHttpServerProvider";
        String robaho = "robaho.net.httpserver.DefaultHttpServerProvider";
        assertFalse(new JdkWebSocketCapability(WebSocketUpgrade.AUTO, jdk).isSupported());
        assertTrue(new JdkWebSocketCapability(WebSocketUpgrade.AUTO, robaho).isSupported());
        assertTrue(new JdkWebSocketCapability(WebSocketUpgrade.ENABLED, jdk).isSupported());
        assertFalse(new JdkWebSocketCapability(WebSocketUpgrade.DISABLED, robaho).isSupported());
        assertTrue(new JdkWebSocketCapability(WebSocketUpgrade.AUTO, jdk).unsupportedMessage().contains(jdk));
        assertTrue(new JdkWebSocketCapability(WebSocketUpgrade.DISABLED, robaho).unsupportedMessage()
            .contains("micronaut.server.jdk.websocket=disabled"));
    }

    @Test
    void theSettingIsReadFromTheConfiguration() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC + "-none",
            "micronaut.server.jdk.websocket", "disabled"))) {
            JdkWebSocketCapability capability = context.getBean(JdkWebSocketCapability.class);
            assertFalse(capability.isSupported());
            assertTrue(capability.unsupportedMessage().contains("disabled"));
        }
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC + "-none",
            "micronaut.server.jdk.websocket", "enabled"))) {
            assertTrue(context.getBean(JdkWebSocketCapability.class).isSupported());
        }
    }

    private static String handshake(int port, String path) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            StringBuilder response = new StringBuilder();
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                response.append(line).append('\n');
            }
            return response.toString();
        }
    }

    @Requires(property = "spec.name", value = SPEC)
    @ServerWebSocket("/unsupported/chat")
    static class UnsupportedChat {
        @OnMessage
        String echo(String message) {
            return message;
        }
    }
}
