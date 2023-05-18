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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;

import java.io.InputStream;
import java.util.concurrent.Callable;

/**
 * API to Read the Body of an HTTP Request where the body is supplied as an InputStream.
 * @author Sergio del Amo
 * @since 4.0.0
 */
@Internal
@FunctionalInterface
public interface BodyBuilder {

    /**
     *
     * @param contentType Content Type
     * @return returns true if the content type is either application/x-www-form-urlencoded or multipart/form-data
     */
    static boolean isFormSubmission(MediaType contentType) {
        return MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(contentType) || MediaType.MULTIPART_FORM_DATA_TYPE.equals(contentType);
    }

    /**
     *
     * @param bodySupplier The HTTP Body supplied as an Input Stream
     * @param request The HTTP Request
     * @return An object representing the HTTP body or null
     */
    @Nullable
    Object buildBody(@NonNull Callable<InputStream> bodySupplier, @NonNull HttpRequest<?> request);
}
