package io.micronaut.http.server.tck.undertow.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages("io.micronaut.http.server.tck.tests")
@SuiteDisplayName("HTTP Server TCK for Undertow")
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest", // asserts the server detects a form binding deadlock; a container that parses the whole form before the route runs has none to detect and completes the request instead
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // unannotated StreamingFileUpload argument: the shared FormFactory builds one, but its completer never emits for a body the container has already parsed
    "io.micronaut.http.server.tck.tests.RemoteAddressTest", // Undertow.getHost() reports an ipv6 address, not 127.0.0.1
    "io.micronaut.http.server.tck.tests.FilterProxyTest", // see https://github.com/micronaut-projects/micronaut-core/issues/9725
})
public class UndertowHttpServerTestSuite {
}
