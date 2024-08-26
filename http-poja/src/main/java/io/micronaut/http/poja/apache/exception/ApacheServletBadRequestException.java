/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.http.poja.apache.exception;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.server.exceptions.HttpServerException;

/**
 * An exception that gets thrown in case of a bad request sent by user.
 * The exception is specific to Apache request parsing.
 */
@Internal
public final class ApacheServletBadRequestException extends HttpServerException {

    /**
     * Create an apache bad request exception.
     *
     * @param message The message to send to user
     * @param cause The cause
     */
    public ApacheServletBadRequestException(String message, Exception cause) {
        super(message, cause);
    }

}
