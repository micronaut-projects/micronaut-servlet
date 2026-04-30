package io.micronaut.servlet.http.server.jdk.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages({
    "io.micronaut.http.server.tck.tests"
})
@SuiteDisplayName("TCK for Built-in Java HTTP Server")
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.FilterProxyTest", // times out on self-proxying requests
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // multipart
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest",
})
public class HttpServerEmbeddedServerSuite {
}
