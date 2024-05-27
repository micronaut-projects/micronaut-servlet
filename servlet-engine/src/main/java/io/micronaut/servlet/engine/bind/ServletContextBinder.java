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
package io.micronaut.servlet.engine.bind;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.servlet.api.ServletAttributes;
import jakarta.servlet.ServletContext;

/**
 * Argument binder for the servlet context.
 */
@Internal
final class ServletContextBinder implements TypedRequestArgumentBinder<ServletContext> {

    public static final @NonNull Argument<ServletContext> TYPE = Argument.of(ServletContext.class);

    @Override
    public Argument<ServletContext> argumentType() {
        return TYPE;
    }

    @Override
    public BindingResult<ServletContext> bind(ArgumentConversionContext<ServletContext> context, HttpRequest<?> source) {
        return () -> source.getAttribute(ServletAttributes.SERVLET_CONTEXT, ServletContext.class);
    }
}
