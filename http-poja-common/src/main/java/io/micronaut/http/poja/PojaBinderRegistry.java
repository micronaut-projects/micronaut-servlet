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

import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Secondary;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.server.binding.DefaultServerRequestBinderRegistry;
import io.micronaut.http.server.binding.ServerBodyAnnotationBinder;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.servlet.http.ServletServerRequestBinderRegistry;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * The binders of POJA: the shared binders of the server.
 *
 * @since 4.10.0
 */
@Internal
@Singleton
@Secondary
@Replaces(DefaultServerRequestBinderRegistry.class)
final class PojaBinderRegistry extends ServletServerRequestBinderRegistry {

    /**
     * @param conversionService The conversion service
     * @param binders           Any registered binders
     * @param bodyBinder        The body binder
     * @param formFactory       The form factory
     */
    @SuppressWarnings({"rawtypes", "java:S3740"}) // the binders of the registry of core
    PojaBinderRegistry(ConversionService conversionService,
                       List<RequestArgumentBinder> binders,
                       ServerBodyAnnotationBinder<Object> bodyBinder,
                       BeanProvider<FormFactory> formFactory) {
        super(conversionService, binders, bodyBinder, formFactory);
    }
}
