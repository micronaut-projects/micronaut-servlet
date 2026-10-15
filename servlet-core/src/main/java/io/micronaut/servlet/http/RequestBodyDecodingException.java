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
package io.micronaut.servlet.http;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.codec.CodecException;

/**
 * A request body the servlet runtime could not decode, answered with {@code 400 Bad Request} by
 * {@link CodecErrorHandler}. A {@link CodecException} of a message body writer is not one: since the exception handlers
 * see the failures of the response writers too, it is answered like on the Netty server, with
 * {@code 500 Internal Server Error}.
 *
 * @author Denis Stepanov
 * @since 6.3.0
 */
@Internal
final class RequestBodyDecodingException extends CodecException {

    /**
     * @param message The message
     * @param cause   The cause
     */
    RequestBodyDecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
