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
    "io.micronaut.http.server.tck.tests.FilterProxyTest", // see https://github.com/micronaut-projects/micronaut-core/issues/9725
    "io.micronaut.http.server.tck.tests.cors.CorsSimpleRequestTest",
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // multipart
})
public class JettyHttpServerTestSuite {
}
