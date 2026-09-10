package io.micronaut.servlet.http.server.jdk.tests;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages({
    "io.micronaut.http.server.tck.tests"
})
@SuiteDisplayName("TCK for Built-in Java HTTP Server")
@ExcludeTags("multipart") // this runtime has a hand written request implementation that does not parse multipart bodies
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // unannotated StreamingFileUpload argument has no typed servlet binder yet
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest", // form binding parks the container thread waiting on a body the same thread must read
    "io.micronaut.http.server.tck.tests.FilterProxyTest", // see https://github.com/micronaut-projects/micronaut-core/issues/9725
    "io.micronaut.http.server.tck.tests.RemoteAddressTest", // the JDK HTTP server reports the bridged client IP in containerized runs, not always 127.0.0.1
})
public class HttpServerEmbeddedServerSuite {
}
