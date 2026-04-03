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
package io.micronaut.http.poja;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.bind.binders.DefaultBodyAnnotationBinder;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.json.JsonMapper;
import io.micronaut.servlet.http.ServletBodyBinder;

/**
 * A {@link ServletBodyBinder} specialization used for POJA serverless requests.
 *
 * @param <T> The body type
 * @since 6.0.0
 */
@Internal
final class PojaBodyBinder<T> extends ServletBodyBinder<T> {

    @SuppressWarnings("unchecked")
    PojaBodyBinder(ConversionService conversionService,
                   MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                   DefaultBodyAnnotationBinder<?> defaultBodyAnnotationBinder,
                   JsonMapper jsonMapper) {
        super(conversionService, messageBodyHandlerRegistry, (DefaultBodyAnnotationBinder<T>) defaultBodyAnnotationBinder, jsonMapper);
    }
}
