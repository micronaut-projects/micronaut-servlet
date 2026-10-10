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

import io.micronaut.context.BeanProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.servlet.http.FormPartBinder;

/**
 * Binds a {@link Part} argument from the fields of the form of the request: a POJA request has no parser of its
 * own, so the fields of a URL encoded or a multipart form are read from the bytes of the body.
 *
 * @param <T> The argument type
 * @since 6.3.0
 */
@Internal
final class PojaPartBinder<T> implements AnnotatedRequestArgumentBinder<Part, T> {

    private final FormPartBinder<T> formPartBinder;

    PojaPartBinder(ConversionService conversionService, BeanProvider<FormFactory> formFactoryProvider) {
        this.formPartBinder = new FormPartBinder<>(conversionService, formFactoryProvider);
    }

    @Override
    public Class<Part> getAnnotationType() {
        return Part.class;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        String boundName = context.getAnnotationMetadata().stringValue(Part.class).orElse(context.getArgument().getName());
        BindingResult<T> result = formPartBinder.bindForm(context, source, boundName, () -> true);
        return result != null ? result : BindingResult.unsatisfied();
    }
}
