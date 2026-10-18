package io.micronaut.servlet.http.server.jdk.websocket.tests;

import io.micronaut.http.server.tck.WebSocketTckTags;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * The WebSocket tests of the server TCK on the built-in JDK HTTP server with the HttpServer
 * provider of io.github.robaho:httpserver. HttpServerEmbeddedServerSuite excludes them: the
 * provider of the JDK it runs on cannot upgrade a connection to WebSocket.
 */
@Suite
@SelectPackages("io.micronaut.http.server.tck.tests.websocket")
@SuiteDisplayName("WebSocket TCK for the built-in Java HTTP Server")
// websocket-ping-pong: the endpoints run on the Jakarta WebSocket API, which answers a ping itself and has no handler
// for it, so the ping handler of a route is never called (the tag also covers the pong tests, which pass)
@ExcludeTags(WebSocketTckTags.WEBSOCKET_PING_PONG)
public class JdkWebSocketTckSuite {
}
