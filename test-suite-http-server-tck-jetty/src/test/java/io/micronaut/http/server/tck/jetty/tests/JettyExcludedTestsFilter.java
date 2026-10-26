/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.http.server.tck.jetty.tests;

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

import java.util.Map;

/**
 * Excludes the single tests of a TCK class this runtime cannot pass, where the suite annotations of
 * {@link JettyHttpServerTestSuite} can only exclude whole classes or tags.
 *
 * <p>Jetty rejects an ambiguous path (an encoded dot segment, an encoded slash, an empty segment) with 400 under its default URI compliance, before the servlet sees the request. A 400 for such a path is a secure answer; the TCK expects the 404 of the Netty server, whose
 * router sees the path. The container is not loosened to get a 404.</p>
 */
public final class JettyExcludedTestsFilter implements PostDiscoveryFilter {

    private static final String STATIC = "io.micronaut.http.server.tck.tests.routing.StaticResourcesRoutesTest#";
    private static final String AMBIGUOUS = "the container answers 400 to an ambiguous or encoded path, the test expects 404";

    private static final Map<String, String> EXCLUDED = Map.of(
        STATIC + "aPathThatLeavesTheBaseIsNotFound", AMBIGUOUS,
        STATIC + "anotherSpellingOfAPathIsNotFound", AMBIGUOUS,
        STATIC + "anEncodedSlashOfAResourceVariableWithASuffixIsNotFound", AMBIGUOUS
    );

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (descriptor.getSource().orElse(null) instanceof MethodSource method) {
            String reason = EXCLUDED.get(method.getClassName() + "#" + method.getMethodName());
            if (reason != null) {
                return FilterResult.excluded(reason);
            }
        }
        return FilterResult.included("not excluded for Jetty");
    }
}
