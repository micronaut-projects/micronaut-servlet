/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.engine.bind;

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.servlet.engine.ServletCompletedFileUpload;
import io.micronaut.servlet.http.ServletExchange;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Optional;

/**
 * Binder for {@link CompletedPart}.
 *
 * @author graemerocher
 * @since 1.0.0
 */
class CompletedPartRequestArgumentBinder implements TypedRequestArgumentBinder<CompletedPart> {
    @Override
    public BindingResult<CompletedPart> bind(
            ArgumentConversionContext<CompletedPart> context,
            HttpRequest<?> source) {
        ServletExchange<?, ?> exchange = (ServletExchange<?, ?>) source;
        final HttpServletRequest nativeRequest = (HttpServletRequest) exchange.getRequest().getNativeRequest();
        final Argument<?> argument = context.getArgument();
        final String partName = context.getAnnotationMetadata().stringValue(Part.class).orElse(argument.getName());
        try {
            javax.servlet.http.Part part = nativeRequest.getPart(partName);
            return () -> Optional.of(new ServletCompletedFileUpload(part));
        } catch (IOException | ServletException e) {
            throw new InternalServerException("Error reading part [" + partName + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public Argument<CompletedPart> argumentType() {
        return Argument.of(CompletedPart.class);
    }
}
