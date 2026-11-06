package io.micronaut.http.server.tck.jetty.tests;

import io.micronaut.http.server.tck.WebSocketTckTags;
import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages({
    "io.micronaut.http.server.tck.tests",
    "io.micronaut.http.server.tck.jetty.tests",
})
@SuiteDisplayName("HTTP Server TCK for Jetty")
// what only the Netty server does: upstream cancellation and streaming back-pressure of a raw proxy, a
// non-blocking route on other threads than the blocking executor (here both are virtual threads), and form
// parts streamed while the request still arrives (the container parses the whole form first); the
// raw connection after a protocol upgrade and HTTP trailers, which a servlet container does not expose
// websocket-ping-handler: a Jakarta WebSocket container answers a ping itself and never hands it to the endpoint,
// so the ping handler of a route is never called
@ExcludeTags({"upstream-cancellation", "streaming-relay", "non-blocking-threads", "streaming-multipart", "protocol-upgrade", "trailers", WebSocketTckTags.WEBSOCKET_PING_HANDLER})
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest", // asserts the server detects a form binding deadlock; a container that parses the whole form before the route runs has none to detect and completes the request instead
})
public class JettyHttpServerTestSuite {
}
