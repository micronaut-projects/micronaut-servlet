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
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SelectPackages({
    "io.micronaut.http.server.tck.tests"
})
@SuiteDisplayName("HTTP Server TCK for POJA")
@ExcludeClassNamePatterns({
    // 19 tests of 188 fail
    // JSON error is not parsed
//    "io.micronaut.http.server.tck.tests.hateoas.JsonErrorSerdeTest",
//    "io.micronaut.http.server.tck.tests.hateoas.JsonErrorTest",
//    "io.micronaut.http.server.tck.tests.hateoas.VndErrorTest",
//    // Cors are not supported and should be handled by a proxy
//    "io.micronaut.http.server.tck.tests.cors.CorsSimpleRequestTest",
//    // See https://github.com/micronaut-projects/micronaut-oracle-cloud/issues/925
//    "io.micronaut.http.server.tck.tests.constraintshandler.ControllerConstraintHandlerTest",
//    // Unclassified
//    "io.micronaut.http.server.tck.tests.FilterProxyTest",
//    "io.micronaut.http.server.tck.tests.filter.RequestFilterExceptionHandlerTest",
//    "io.micronaut.http.server.tck.tests.filter.RequestFilterTest",
//    "io.micronaut.http.server.tck.tests.filter.ResponseFilterTest",
//    "io.micronaut.http.server.tck.tests.staticresources.StaticResourceTest",
})
public class PojaServerTestSuite {
}
