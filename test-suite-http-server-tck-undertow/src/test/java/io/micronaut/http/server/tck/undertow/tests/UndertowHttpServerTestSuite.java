package io.micronaut.http.server.tck.undertow.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages("io.micronaut.http.server.tck.tests")
@SuiteDisplayName("HTTP Server TCK for Undertow")
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.cors.CorsSimpleRequestTest",
    "io.micronaut.http.server.tck.tests.RemoteAddressTest",
    "io.micronaut.http.server.tck.tests.staticresources.StaticResourceTest", // fails on GraalVm
    "io.micronaut.http.server.tck.tests.filter.ClientResponseFilterTest", // fails on GraalVM
    "io.micronaut.http.server.tck.tests.ErrorHandlerTest" // fails on GraalVM
})
public class UndertowHttpServerTestSuite {
}
