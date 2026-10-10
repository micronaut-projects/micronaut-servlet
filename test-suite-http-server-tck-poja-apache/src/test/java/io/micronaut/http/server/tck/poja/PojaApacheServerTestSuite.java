/*
 * Copyright 2017-2023 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.server.tck.poja;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages({
    "io.micronaut.http.server.tck.tests"
})
@SuiteDisplayName("HTTP Server TCK for POJA")
// single tests of a class are excluded by PojaExcludedTestsFilter
// multipart: this runtime has a hand written request implementation that does not parse multipart bodies; the
// others are what only the Netty server does: upstream cancellation and streaming back-pressure of a raw proxy,
// and a non-blocking route on other threads than the blocking executor (here both are virtual threads); the raw
// connection after a protocol upgrade and HTTP trailers, which a servlet container does not expose
@ExcludeTags({"multipart", "upstream-cancellation", "streaming-relay", "non-blocking-threads", "streaming-multipart", "protocol-upgrade", "trailers"})
@ExcludeClassNamePatterns({
    "io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest", // asserts the server detects a form binding deadlock; a container that parses the whole form before the route runs has none to detect and completes the request instead
    "io.micronaut.http.server.tck.tests.cors.SimpleRequestWithCorsNotEnabledTest", // posts multipart to /refresh; the unconsumed multipart body desynchronises the single POJA input stream
    // See https://github.com/micronaut-projects/micronaut-oracle-cloud/issues/925
    "io.micronaut.http.server.tck.tests.constraintshandler.ControllerConstraintHandlerTest",
    // Proxying is probably not supported. There is no request concurrency
    "io.micronaut.http.server.tck.tests.FilterProxyTest",
})
public class PojaApacheServerTestSuite {
}
