/*
 * Copyright 2017-2020 original authors
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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Indexed;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.MutableHttpResponse;
import org.reactivestreams.Publisher;



/**
 * An interface for custom encoding of the HTTP response.
 *
 * @author graemerocher
 * @since 1.0.0
 * @param <T> The response type
 */
@Indexed(ServletResponseEncoder.class)
public interface ServletResponseEncoder<T> {

    /**
     * @return The response type.
     */
    Class<T> getResponseType();

    /**
     * Encode the given value.
     * @param exchange The change
     * @param annotationMetadata The annotation metadata declared on the method
     * @param value The value to encode
     * @return A publisher that emits completes with the response once the value has been encoded
     */
    Publisher<MutableHttpResponse<?>> encode(
            @NonNull ServletExchange<?, ?> exchange,
            AnnotationMetadata annotationMetadata,
            @NonNull T value);
}
