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
    "io.micronaut.http.server.tck.tests.cors.CorsSimpleRequestTest", // the two rejection cases race the client's upload: Jetty answers 403 without reading the multipart body and closes the connection, so the client sees an IOException instead of the status. Passes on Tomcat and Undertow. See https://github.com/micronaut-projects/micronaut-servlet/issues/929
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest", // asserts the server detects a form binding deadlock; a container that parses the whole form before the route runs has none to detect and completes the request instead
    "io.micronaut.http.server.tck.tests.forms.UploadTest", // unannotated StreamingFileUpload argument: the shared FormFactory builds one, but its completer never emits for a body the container has already parsed
    "io.micronaut.http.server.tck.tests.FilterProxyTest", // see https://github.com/micronaut-projects/micronaut-core/issues/9725
    "io.micronaut.http.server.tck.tests.cors.CorsDisabledByDefaultTest", // asserts no Vary header at all; Jetty's GzipHandler adds Vary: Accept-Encoding to every compressible response, which is unrelated to CORS. Fixed in the core TCK (micronaut-core#13147) to check only for Vary: Origin; re-enable once that release is used here
    "io.micronaut.http.server.tck.tests.cors.SimpleRequestWithCorsNotEnabledTest", // same Vary: Accept-Encoding assertion as above
})
public class JettyHttpServerTestSuite {
}
