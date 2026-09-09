/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.servlet.http.server.jdk;

import org.jspecify.annotations.NonNull;

import java.io.IOException;

/**
 * Writes the status line and headers of a response to the underlying exchange. Invoked at most once per response,
 * either when the body is about to be written or, for a body-less response, once the exchange has been handled.
 */
@FunctionalInterface
interface ResponseHeadersCommitter {
    /**
     * @param response The response whose status and headers should be sent
     * @throws IOException If the headers could not be sent
     */
    void commit(@NonNull HttpExchangeHttpServletResponse response) throws IOException;
}
