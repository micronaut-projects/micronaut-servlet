/*
 * Copyright 2017-2022 original authors
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

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.response.Error;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import jakarta.inject.Singleton;

/**
 * Error mapper for {@link CodecException}.
 *
 * @author Denis Stepanov
 * @since 4.0.0
 */
@Singleton
@Produces
final class CodecErrorHandler implements ExceptionHandler<CodecException, HttpResponse<?>> {
    private final ErrorResponseProcessor<?> responseProcessor;

    public CodecErrorHandler(ErrorResponseProcessor<?> responseProcessor) {
        this.responseProcessor = responseProcessor;
    }

    public HttpResponse<?> handle(HttpRequest request, CodecException exception) {
        return this.responseProcessor.processResponse(ErrorContext.builder(request).cause(exception).error(new Error() {
            @Override
            public String getMessage() {
                return exception.getMessage();
            }
        }).build(), HttpResponse.badRequest());
    }
}
