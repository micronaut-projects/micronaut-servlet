package io.micronaut.servlet.http.server.tests;

import org.junit.platform.suite.api.*;

@Suite
@SelectPackages({
    "io.micronaut.http.server.tck.tests"
})
@SuiteDisplayName("TCK for Built-in Java HTTP Server")
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.FilterProxyTest", // see https://github.com/micronaut-projects/micronaut-core/issues/9725
})
public class HttpServerEmbeddedServerSuite {
}
