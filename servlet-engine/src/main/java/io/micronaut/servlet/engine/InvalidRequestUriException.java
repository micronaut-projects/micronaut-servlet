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
package io.micronaut.servlet.engine;

import java.io.Serial;

/**
 * Thrown when the request URI and query string the container passed on do not form a valid URI, for example because
 * of a malformed escape sequence in the query string. {@link DefaultServletHttpHandler} answers such a request with a
 * 400 before throwing it from {@link DefaultServletHttpHandler#exchange(jakarta.servlet.http.HttpServletRequest,
 * jakarta.servlet.http.HttpServletResponse)}; {@link DefaultServletHttpHandler#service(jakarta.servlet.http.HttpServletRequest,
 * jakarta.servlet.http.HttpServletResponse)} does not throw it at all.
 *
 * <p>Extends {@link IllegalArgumentException}, which is what {@link java.net.URI#create(String)} threw before.</p>
 *
 * @since 6.2.3
 */
public final class InvalidRequestUriException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    InvalidRequestUriException(IllegalArgumentException cause) {
        super("Invalid request URI: " + cause.getMessage(), cause);
    }
}
