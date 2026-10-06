package io.micronaut.http.server.tck.undertow.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages("io.micronaut.http.server.tck.tests")
@SuiteDisplayName("HTTP Server TCK for Undertow")
// what only the Netty server does: upstream cancellation and streaming back-pressure of a raw proxy, a
// non-blocking route on other threads than the blocking executor (here both are virtual threads), and form
// parts streamed while the request still arrives (the container parses the whole form first)
@ExcludeTags({"upstream-cancellation", "streaming-relay", "non-blocking-threads", "streaming-multipart"})
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.filter.FilterMutatedRequestTest", // the mutable request view a filter continues with is not implemented yet: the route loses the connection and the body. See https://github.com/micronaut-projects/micronaut-servlet/issues/1143
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest", // asserts the server detects a form binding deadlock; a container that parses the whole form before the route runs has none to detect and completes the request instead
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // unannotated StreamingFileUpload argument: the shared FormFactory builds one, but its completer never emits for a body the container has already parsed
    "io.micronaut.http.server.tck.tests.RemoteAddressTest", // Undertow.getHost() reports an ipv6 address, not 127.0.0.1
})
public class UndertowHttpServerTestSuite {
}
