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
    // 74 tests of 188 fail
    "io.micronaut.http.server.tck.tests.constraintshandler.ControllerConstraintHandlerTest",
    "io.micronaut.http.server.tck.tests.cors.CorsDisabledByDefaultTest",
    "io.micronaut.http.server.tck.tests.cors.CorsSimpleRequestTest",
    "io.micronaut.http.server.tck.tests.cors.CrossOriginTest",
    "io.micronaut.http.server.tck.tests.ErrorHandlerTest",
    "io.micronaut.http.server.tck.tests.ExpressionTest",
    "io.micronaut.http.server.tck.tests.FilterErrorTest",
    "io.micronaut.http.server.tck.tests.FilterProxyTest",
    "io.micronaut.http.server.tck.tests.FiltersTest",
    "io.micronaut.http.server.tck.tests.HeadersTest",
    "io.micronaut.http.server.tck.tests.endpoints.health.HealthResultTest",
    "io.micronaut.http.server.tck.tests.endpoints.health.HealthTest",
    "io.micronaut.http.server.tck.tests.bodywritable.HtmlBodyWritableTest",
    "io.micronaut.http.server.tck.tests.filter.HttpServerFilterTest",
    "io.micronaut.http.server.tck.tests.codec.JsonCodecAdditionalTypeTest",
    "io.micronaut.http.server.tck.tests.hateoas.JsonErrorSerdeTest",
    "io.micronaut.http.server.tck.tests.hateoas.JsonErrorTest",
    "io.micronaut.http.server.tck.tests.LocalErrorReadingBodyTest",
    "io.micronaut.http.server.tck.tests.MissingBodyAnnotationTest",
    "io.micronaut.http.server.tck.tests.OctetTest",
    "io.micronaut.http.server.tck.tests.filter.options.OptionsFilterTest",
    "io.micronaut.http.server.tck.tests.PublisherExceptionHandlerTest",
    "io.micronaut.http.server.tck.tests.RemoteAddressTest",
    "io.micronaut.http.server.tck.tests.filter.RequestFilterExceptionHandlerTest",
    "io.micronaut.http.server.tck.tests.filter.RequestFilterTest",
    "io.micronaut.http.server.tck.tests.filter.ResponseFilterTest",
    "io.micronaut.http.server.tck.tests.staticresources.StaticResourceTest",
    "io.micronaut.http.server.tck.tests.textplain.TxtPlainBigDecimalTest",
    "io.micronaut.http.server.tck.tests.hateoas.VndErrorTest",
})
public class PojaServerTestSuite {
}
