package io.micronaut.http.server.tck.undertow.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages("io.micronaut.http.server.tck.tests")
@SuiteDisplayName("HTTP Server TCK for Undertow")
@ExcludeTags("multipart") // multipart form fields are not bound to controller/endpoint parameters on servlet runtimes yet; the CORS refresh tests only passed on core 5.1.x because the first environment refresh reported a spurious diff
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.RemoteAddressTest", // Undertow.getHost() reports an ipv6 address, not 127.0.0.1
    "io.micronaut.http.server.tck.tests.FilterProxyTest", // see https://github.com/micronaut-projects/micronaut-core/issues/9725
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // multipart
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest",
})
public class UndertowHttpServerTestSuite {
}
