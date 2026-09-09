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
@ExcludeTags("multipart") // multipart form fields are not bound to controller/endpoint parameters on servlet runtimes yet; the CORS refresh tests only passed on core 5.1.x because the first environment refresh reported a spurious diff
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.NoBodyResponseTest", // JDK HttpServer sends chunked headers for body-less 200 responses and closes the exchange without a terminator, see https://github.com/micronaut-projects/micronaut-servlet/issues/1117
    "io.micronaut.http.server.tck.tests.FilterProxyTest", // see https://github.com/micronaut-projects/micronaut-core/issues/9725
    "io.micronaut.http.server.tck.tests.RemoteAddressTest", // the JDK HTTP server reports the bridged client IP in containerized runs, not always 127.0.0.1
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // multipart
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest",
})
public class HttpServerEmbeddedServerSuite {
}
