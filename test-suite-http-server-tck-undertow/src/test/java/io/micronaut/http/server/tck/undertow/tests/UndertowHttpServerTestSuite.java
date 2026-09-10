package io.micronaut.http.server.tck.undertow.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages("io.micronaut.http.server.tck.tests")
@SuiteDisplayName("HTTP Server TCK for Undertow")
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // unannotated StreamingFileUpload argument has no typed servlet binder yet
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest", // form binding parks the container thread waiting on a body the same thread must read
    "io.micronaut.http.server.tck.tests.RemoteAddressTest", // Undertow.getHost() reports an ipv6 address, not 127.0.0.1
    "io.micronaut.http.server.tck.tests.FilterProxyTest", // see https://github.com/micronaut-projects/micronaut-core/issues/9725
})
public class UndertowHttpServerTestSuite {
}
