package io.micronaut.http.server.tck.jetty.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages("io.micronaut.http.server.tck.tests")
@SuiteDisplayName("HTTP Server TCK for Jetty")
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.staticresources.StaticResourceTest", // Graal fails to see /assets from the TCK as a resource https://ge.micronaut.io/s/ufuhtbe5sgmxi
    "io.micronaut.http.server.tck.tests.filter.ClientResponseFilterTest", // responseFilterThrowableParameter fails under Graal https://ge.micronaut.io/s/ufuhtbe5sgmxi
})
public class JettyHttpServerTestSuite {
}
