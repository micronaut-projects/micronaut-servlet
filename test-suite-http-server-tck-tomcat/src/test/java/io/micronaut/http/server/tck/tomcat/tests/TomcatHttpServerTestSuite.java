package io.micronaut.http.server.tck.tomcat.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages("io.micronaut.http.server.tck.tests")
@SuiteDisplayName("HTTP Server TCK for Tomcat")
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.cors.CorsSimpleRequestTest",
    "io.micronaut.http.server.tck.tests.MiscTest",
    "io.micronaut.http.server.tck.tests.RemoteAddressTest",
    "io.micronaut.http.server.tck.tests.filter.ClientResponseFilterTest" // fails on GraalVM
})
public class TomcatHttpServerTestSuite {
}
