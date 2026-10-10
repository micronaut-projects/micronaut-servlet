package io.micronaut.servlet.http.server.jdk.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages({
    "io.micronaut.http.server.tck.tests"
})
@SuiteDisplayName("TCK for Built-in Java HTTP Server")
// multipart: this runtime has a hand written request implementation that does not parse multipart bodies; the
// others are what only the Netty server does: upstream cancellation and streaming back-pressure of a raw proxy,
// and a non-blocking route on other threads than the blocking executor (here both are virtual threads); the raw
// connection after a protocol upgrade and HTTP trailers, which a servlet container does not expose
@ExcludeTags({"multipart", "upstream-cancellation", "streaming-relay", "non-blocking-threads", "streaming-multipart", "protocol-upgrade", "trailers"})
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest", // asserts the server detects a form binding deadlock; a container that parses the whole form before the route runs has none to detect and completes the request instead
    "io.micronaut.http.server.tck.tests.RemoteAddressTest", // the JDK HTTP server reports the bridged client IP in containerized runs, not always 127.0.0.1
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // its only test posts a multipart body, which this runtime does not parse (see the multipart tag above)
})
public class HttpServerEmbeddedServerSuite {
}
