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
package io.micronaut.http.poja;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.bind.DefaultRequestBinderRegistry;
import io.micronaut.http.bind.binders.DefaultBodyAnnotationBinder;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.servlet.http.ServletBinderRegistry;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * An argument binder registry implementation for serverless POJA applications.
 */
@Internal
@Singleton
@Replaces(DefaultRequestBinderRegistry.class)
class PojaBinderRegistry extends ServletBinderRegistry {

    /**
     * Default constructor.
     *  @param mediaTypeCodecRegistry   The media type codec registry
     * @param conversionService         The conversion service
     * @param binders                   Any registered binders
     * @param defaultBodyAnnotationBinder The default binder
     */
    public PojaBinderRegistry(MediaTypeCodecRegistry mediaTypeCodecRegistry,
                              ConversionService conversionService,
                              List<RequestArgumentBinder> binders,
                              DefaultBodyAnnotationBinder<?> defaultBodyAnnotationBinder
    ) {
        super(mediaTypeCodecRegistry, conversionService, binders, defaultBodyAnnotationBinder);

        this.byAnnotation.put(Body.class, new PojaBodyBinder<>(conversionService, mediaTypeCodecRegistry, defaultBodyAnnotationBinder));
    }
}
