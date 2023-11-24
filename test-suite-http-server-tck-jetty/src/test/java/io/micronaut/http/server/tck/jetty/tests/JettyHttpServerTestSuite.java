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
    "io.micronaut.http.server.tck.tests.staticresources.StaticResourceTest", // Graal fails to see /assets from the TCK as a resource https://ge.micronaut.io/s/ufuhtbe5sgmxi
    "io.micronaut.http.server.tck.tests.filter.ClientResponseFilterTest", // responseFilterThrowableParameter fails under Graal https://ge.micronaut.io/s/ufuhtbe5sgmxi
    "io.micronaut.http.server.tck.tests.FilterProxyTest", // see https://github.com/micronaut-projects/micronaut-core/issues/9725
    "io.micronaut.http.server.tck.tests.LocalErrorReadingBodyTest", // Cannot read body as text once stream is exhausted trying to read it as a different type See https://github.com/micronaut-projects/micronaut-servlet/pull/548
    "io.micronaut.http.server.tck.tests.filter.options.OptionsFilterTest" // needs https://github.com/micronaut-projects/micronaut-core/pull/10126
})
public class JettyHttpServerTestSuite {
}
