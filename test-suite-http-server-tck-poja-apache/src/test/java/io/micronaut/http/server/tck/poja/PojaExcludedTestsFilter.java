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

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

import java.util.Map;

/**
 * Excludes the single tests of a TCK class this runtime cannot pass, where the suite annotations of
 * {@link PojaApacheServerTestSuite} can only exclude whole classes or tags.
 *
 * <p>The blocking executor of POJA runs a task on the thread that submits it, which is the thread of the request
 * (see {@code ApacheExecutorServiceFactory}), and so does the executor the body of the request is read on. A test
 * that needs the route, or the reading of the body, on a thread of its own while the request thread writes the
 * response cannot pass.</p>
 */
public final class PojaExcludedTestsFilter implements PostDiscoveryFilter {

    private static final String ROUTING = "io.micronaut.http.server.tck.tests.routing.";

    private static final Map<String, String> EXCLUDED = Map.of(
        ROUTING + "HandlerRouteServerSentEventsTest#blockingHandlerFailureAfterTheFirstEventEndsTheStreamAbruptly",
        "the blocking sender waits until the connection took the event, which the request thread writes once the sender returned",
        ROUTING + "HandlerRouteServerSentEventsTest#slowReaderPausesABlockingSender",
        "the blocking sender waits until the connection took the event, which the request thread writes once the sender returned",
        ROUTING + "HandlerRouteBodyElementsTest#streamingByteBodyElementIsWrittenAsItArrives",
        "the request body is read to its end on the request thread before the response starts",
        ROUTING + "HandlerRouteGroupSettingsTest#theRoutesOfAGroupRunOnItsExecutorUnlessTheyChooseTheirThread",
        "a task submitted to the blocking executor runs on the thread that submits it: the test thread, not a thread of the server"
    );

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (descriptor.getSource().orElse(null) instanceof MethodSource method) {
            String reason = EXCLUDED.get(method.getClassName() + "#" + method.getMethodName());
            if (reason != null) {
                return FilterResult.excluded(reason);
            }
        }
        return FilterResult.included("not excluded for POJA");
    }
}
