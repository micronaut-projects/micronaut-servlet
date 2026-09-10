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
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // unannotated StreamingFileUpload argument: the shared FormFactory builds one, but its completer never emits for a body the container has already parsed
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest", // form binding parks the container thread waiting on a body the same thread must read
    "io.micronaut.http.server.tck.tests.FilterProxyTest", // see https://github.com/micronaut-projects/micronaut-core/issues/9725
})
public class JettyHttpServerTestSuite {
}
