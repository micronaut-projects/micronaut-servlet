package io.micronaut.http.server.tck.jetty.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages({
    "io.micronaut.http.server.tck.tests",
    "io.micronaut.http.server.tck.jetty.tests",
})
@SuiteDisplayName("HTTP Server TCK for Jetty")
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.cors.CorsSimpleRequestTest",
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // multipart
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest",
})
public class JettyHttpServerTestSuite {
}
