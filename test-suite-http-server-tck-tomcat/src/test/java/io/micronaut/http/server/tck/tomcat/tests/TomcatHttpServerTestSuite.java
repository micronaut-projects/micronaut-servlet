package io.micronaut.http.server.tck.tomcat.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages("io.micronaut.http.server.tck.tests")
@SuiteDisplayName("HTTP Server TCK for Tomcat")
@ExcludeClassNamePatterns(value = "io.micronaut.http.server.tck.tests.BodyTest|io.micronaut.http.server.tck.tests.cors.CorsSimpleRequestTest|io.micronaut.http.server.tck.tests.MiscTest|io.micronaut.http.server.tck.tests.ErrorHandlerTest")
public class TomcatHttpServerTestSuite {
}
