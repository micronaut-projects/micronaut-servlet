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
import io.micronaut.http.body.AsyncRequestBodySource;
import org.jspecify.annotations.Nullable;

/**
 * A request that gives the {@link io.micronaut.http.body.AsyncRequestBody} of
 * {@link io.micronaut.http.body.AsyncRequestBody#of(io.micronaut.http.HttpRequest)}, from the source the
 * {@link ServletHttpHandler} sets when it receives the request.
 *
 * @author Denis Stepanov
 * @since 6.3.0
 */
@Internal
public interface AsyncRequestBodySourceHolder extends AsyncRequestBodySource {

    /**
     * @param source The source of the bodies of the request
     */
    void setAsyncRequestBodySource(@Nullable AsyncRequestBodySource source);
}
