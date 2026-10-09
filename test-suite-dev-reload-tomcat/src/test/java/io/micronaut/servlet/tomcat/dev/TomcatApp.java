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

import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.dev.tck.ReloadHarness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The application the tests reload: a controller, and a servlet that is not Micronaut's mounted beside it.
 */
final class TomcatApp {

    static final String CONTROLLER = """
        package example;

        import io.micronaut.http.annotation.Controller;
        import io.micronaut.http.annotation.Get;
        import io.micronaut.http.annotation.Produces;
        import io.micronaut.http.MediaType;

        @Controller("/hello")
        public class HelloController {
            @Get
            @Produces(MediaType.TEXT_PLAIN)
            public String hello() {
                return "%s";
            }
        }
        """;

    static final String SLOW_CONTROLLER = """
        package example;

        import io.micronaut.http.annotation.Controller;
        import io.micronaut.http.annotation.Get;
        import io.micronaut.http.annotation.Produces;
        import io.micronaut.http.MediaType;
        import io.micronaut.scheduling.TaskExecutors;
        import io.micronaut.scheduling.annotation.ExecuteOn;

        @Controller("/slow")
        public class SlowController {
            @Get
            @Produces(MediaType.TEXT_PLAIN)
            @ExecuteOn(TaskExecutors.BLOCKING)
            public String slow() throws InterruptedException {
                Thread.sleep(%d);
                return "%s";
            }
        }
        """;

    static final String PLAIN_SERVLET = """
        package example;

        import jakarta.servlet.GenericServlet;
        import jakarta.servlet.ServletRequest;
        import jakarta.servlet.ServletResponse;
        import jakarta.servlet.annotation.WebServlet;
        import java.io.IOException;

        @WebServlet("/plain/*")
        public class PlainServlet extends GenericServlet {
            @Override
            public void service(ServletRequest req, ServletResponse res) throws IOException {
                res.setContentType("text/plain");
                res.getWriter().write("plain %s");
            }
        }
        """;

    static final String SESSION_SERVLET = """
        package example;

        import jakarta.servlet.annotation.WebServlet;
        import jakarta.servlet.http.HttpServlet;
        import jakarta.servlet.http.HttpServletRequest;
        import jakarta.servlet.http.HttpServletResponse;
        import jakarta.servlet.http.HttpSession;
        import java.io.IOException;

        @WebServlet("/session")
        public class SessionServlet extends HttpServlet {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                HttpSession session = req.getSession(true);
                String set = req.getParameter("set");
                if (set != null) {
                    session.setAttribute("value", set);
                }
                res.setContentType("text/plain");
                res.getWriter().write("%s " + (session.isNew() ? "new" : "existing") + " " + session.getAttribute("value"));
            }
        }
        """;

    static final String WEBSOCKET_ENDPOINT = """
        package example;

        import jakarta.websocket.OnMessage;
        import jakarta.websocket.server.ServerEndpoint;

        @ServerEndpoint("/echo")
        public class EchoEndpoint {
            @OnMessage
            public String echo(String message) {
                return "%s " + message;
            }
        }
        """;

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private TomcatApp() {
    }

    /**
     * Configures the application on a free port.
     *
     * @param harness The harness
     * @param extra More properties
     * @return The port
     */
    static int properties(ReloadHarness harness, Map<String, String> extra) {
        int port = SocketUtils.findAvailableTcpPort();
        configuration(port, extra).forEach(harness::property);
        return port;
    }

    static Map<String, String> configuration(int port, Map<String, String> extra) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("micronaut.server.port", String.valueOf(port));
        properties.putAll(extra);
        return properties;
    }

    static String propertiesText(int port, Map<String, String> extra) {
        StringBuilder text = new StringBuilder();
        configuration(port, extra).forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        return text.toString();
    }

    /**
     * A GET, answered with the status and the body, or with the failure.
     *
     * @param port The port
     * @param path The path
     * @return The status and the body
     */
    static String get(int port, String path) {
        try {
            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : response.statusCode() + " " + response.body();
        } catch (IOException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }
}
