package io.micronaut.http.server.tck.undertow.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages("io.micronaut.http.server.tck.tests")
@SuiteDisplayName("HTTP Server TCK for Undertow")
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.RemoteAddressTest", // Undertow.getHost() reports an ipv6 address, not 127.0.0.1
    "io.micronaut.http.server.tck.tests.staticresources.StaticResourceTest", // Graal fails to see /assets from the TCK as a resource https://ge.micronaut.io/s/ufuhtbe5sgmxi
    "io.micronaut.http.server.tck.tests.filter.ClientResponseFilterTest", // responseFilterThrowableParameter fails under Graal https://ge.micronaut.io/s/ufuhtbe5sgmxi
    "io.micronaut.http.server.tck.tests.codec.JsonCodeAdditionalTypeTest", // remove once this pr is merged https://github.com/micronaut-projects/micronaut-core/pull/9419
})
public class UndertowHttpServerTestSuite {
}
