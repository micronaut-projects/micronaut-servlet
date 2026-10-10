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
final class UndertowApp {

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
                res.setContentType("text/plain");
                if (req.getParameter("created") != null) {
                    res.getWriter().write(String.valueOf(session.getCreationTime()));
                    return;
                }
                if (req.getParameter("id") != null) {
                    res.getWriter().write(session.getId());
                    return;
                }
                if (req.getParameter("interval") != null) {
                    res.getWriter().write(String.valueOf(session.getMaxInactiveInterval()));
                    return;
                }
                String set = req.getParameter("set");
                if (set != null) {
                    session.setAttribute("value", set);
                    session.setAttribute("cart", new Cart(set));
                    // not serializable
                    session.setAttribute("opaque", new Object());
                    session.setAttribute("counter", new Counter());
                }
                String ttl = req.getParameter("ttl");
                if (ttl != null) {
                    session.setMaxInactiveInterval(Integer.parseInt(ttl));
                }
                Object cart = session.getAttribute("cart");
                res.getWriter().write("%s " + (session.isNew() ? "new" : "existing")
                    + " value=" + session.getAttribute("value")
                    + " cart=" + (cart == null ? "null" : cart + (cart.getClass() == Cart.class ? "/current" : "/stale"))
                    + " opaque=" + (session.getAttribute("opaque") == null ? "null" : "set")
                    + " counter=" + session.getAttribute("counter"));
            }
        }
        """;

    static final String SESSION_CART = """
        package example;

        import java.io.Serializable;

        public class Cart implements Serializable {
            private final String item;

            public Cart(String item) {
                this.item = item;
            }

            @Override
            public String toString() {
                return item;
            }
        }
        """;

    static final String SESSION_COUNTER = """
        package example;

        import java.io.Serializable;

        public class Counter implements Serializable {
            private static final long serialVersionUID = %dL;

            @Override
            public String toString() {
                return String.valueOf(serialVersionUID);
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

    /**
     * An HTML page, as each servlet and route of the LiveReload test writes it.
     */
    static final String PAGE = "<html><head><title>page</title></head><body><p>hi</p></body></html>";

    /**
     * A body that is not HTML, though it reads like the end of a page.
     */
    static final String NOT_HTML = "{\"html\":\"</body>\"}";

    private static final String LIVE_RELOAD_SERVLETS = """
        package example;

        import jakarta.servlet.annotation.WebServlet;
        import jakarta.servlet.http.HttpServlet;
        import jakarta.servlet.http.HttpServletRequest;
        import jakarta.servlet.http.HttpServletResponse;
        import java.io.IOException;
        import java.nio.charset.StandardCharsets;

        public final class LiveReloadServlets {

            static final String PAGE = "%s";
            static final String NOT_HTML = "%s";

            @WebServlet("/plain-writer")
            public static class Writer extends HttpServlet {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                    res.setContentType("text/html;charset=UTF-8");
                    res.getWriter().write(PAGE);
                }
            }

            @WebServlet("/plain-stream")
            public static class Stream extends HttpServlet {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                    byte[] page = PAGE.getBytes(StandardCharsets.UTF_8);
                    res.setContentType("text/html");
                    res.setContentLength(page.length);
                    res.getOutputStream().write(page);
                }
            }

            @WebServlet("/plain-streamed")
            public static class Streamed extends HttpServlet {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                    res.setContentType("text/html;charset=UTF-8");
                    int end = PAGE.indexOf("</body>");
                    res.getWriter().write(PAGE.substring(0, end));
                    res.flushBuffer();
                    res.getWriter().write(PAGE.substring(end));
                }
            }

            @WebServlet("/plain-late-csp")
            public static class LateCsp extends HttpServlet {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                    res.setContentType("text/html;charset=UTF-8");
                    res.getWriter().write(PAGE);
                    res.setHeader("Content-Security-Policy", "script-src 'self'");
                }
            }

            @WebServlet("/plain-partial")
            public static class Partial extends HttpServlet {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                    res.setStatus(206);
                    res.setContentType("text/html;charset=UTF-8");
                    res.getWriter().write(PAGE);
                }
            }

            @WebServlet(value = "/plain-async-dispatch", asyncSupported = true)
            public static class AsyncDispatch extends HttpServlet {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                    res.setContentType("text/html;charset=UTF-8");
                    res.getWriter().write(PAGE.substring(0, 10));
                    req.startAsync().dispatch("/plain-async-rest");
                }
            }

            @WebServlet(value = "/plain-async-rest", asyncSupported = true)
            public static class AsyncRest extends HttpServlet {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                    res.getWriter().write(PAGE.substring(10));
                }
            }

            @WebServlet("/plain-json")
            public static class Json extends HttpServlet {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                    res.setContentType("application/json");
                    res.getWriter().write(NOT_HTML);
                }
            }
        }
        """;

    private static final String LIVE_RELOAD_CONTROLLER = """
        package example;

        import io.micronaut.http.MediaType;
        import io.micronaut.http.annotation.Controller;
        import io.micronaut.http.annotation.Get;
        import io.micronaut.http.annotation.Produces;

        @Controller("/page")
        public class PageController {
            @Get
            @Produces(MediaType.TEXT_HTML)
            public String page() {
                return "%s";
            }
        }
        """;

    /**
     * The servlets, the route and the static files of the LiveReload test.
     *
     * @param harness The harness
     */
    static void liveReloadSources(ReloadHarness harness) {
        String page = PAGE.replace("\"", "\\\"");
        String notHtml = NOT_HTML.replace("\"", "\\\"");
        harness.source("example.LiveReloadServlets", LIVE_RELOAD_SERVLETS.formatted(page, notHtml));
        harness.source("example.PageController", LIVE_RELOAD_CONTROLLER.formatted(page));
        harness.resource("site/index.html", PAGE);
        harness.resource("site/notes.txt", NOT_HTML);
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private UndertowApp() {
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
