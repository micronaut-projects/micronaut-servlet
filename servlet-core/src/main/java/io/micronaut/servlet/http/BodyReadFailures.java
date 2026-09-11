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
package io.micronaut.servlet.http;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.exceptions.HttpException;
import org.jspecify.annotations.Nullable;

/**
 * Tells a failure the request body itself raised, such as {@link io.micronaut.http.exceptions.ContentLengthExceededException}
 * when {@code micronaut.server.max-request-size} is exceeded, apart from a failure to decode it. The streaming body
 * delivers its errors inside an {@link java.io.IOException} when read as a stream, and a codec may wrap that again,
 * so the original exception has to be dug out of the cause chain for it to map to its own status.
 *
 * @since 6.2.0
 */
@Internal
final class BodyReadFailures {
    private static final int MAX_CAUSE_DEPTH = 8;

    private BodyReadFailures() {
    }

    /**
     * Finds an HTTP failure in the cause chain of a body read error.
     *
     * @param e The failure
     * @return The HTTP failure, or {@code null} if the failure is a decoding problem or unrelated
     */
    /**
     * For a runtime failure raised while reading the body, such as the unchecked exceptions of a JSON parser
     * wrapping a stream error: the HTTP failure in its cause chain if there is one, otherwise the failure itself.
     *
     * @param e The failure
     * @return The exception to throw
     */
    static RuntimeException httpFailureOr(RuntimeException e) {
        HttpException httpException = httpFailure(e);
        return httpException != null ? httpException : e;
    }

    static @Nullable HttpException httpFailure(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof HttpException httpException && !(current instanceof CodecException)) {
                // a codec failure is a decoding problem to report as such, not a status of its own
                return httpException;
            }
            current = current.getCause();
        }
        return null;
    }
}
