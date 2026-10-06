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
package io.micronaut.servlet.engine.bind;

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.HttpRequestWrapper;
import io.micronaut.http.LifecycleHttpRequest;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.servlet.engine.ServletParts;
import io.micronaut.servlet.http.ServletHttpRequest;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.util.Optional;

/**
 * Binder for {@link CompletedPart}.
 *
 * @author graemerocher
 * @since 1.0.0
 */
class CompletedPartRequestArgumentBinder implements TypedRequestArgumentBinder<CompletedPart> {
    private final HttpServerConfiguration configuration;

    CompletedPartRequestArgumentBinder(HttpServerConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public BindingResult<CompletedPart> bind(
            ArgumentConversionContext<CompletedPart> context,
            HttpRequest<?> source) {
        // the request itself, or the servlet request under the wrappers of filters
        ServletHttpRequest<?, ?> servletRequest = servletRequest(source);
        if (servletRequest == null) {
            return BindingResult.UNSATISFIED;
        }
        final HttpServletRequest nativeRequest = (HttpServletRequest) servletRequest.getNativeRequest();
        final Argument<?> argument = context.getArgument();
        final String partName = context.getAnnotationMetadata().stringValue(Part.class).orElse(argument.getName());
        try {
            jakarta.servlet.http.Part part = ServletParts.part(nativeRequest, partName);
            if (part == null) {
                return BindingResult.UNSATISFIED;
            }
            if ((part.getSubmittedFileName() == null || part.getSubmittedFileName().isEmpty()) && CompletedFileUpload.class.isAssignableFrom(argument.getType())) {
                // a text field is not a file, answered like the form factory of the other runtimes
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Field [" + part.getName() + "] was expected to be a file upload, but is missing a file name");
            }
            @SuppressWarnings("java:S2095")
            CompletedPart completedPart = ServletCompletedFileUploadFactory.create(configuration, part);
            if (source instanceof LifecycleHttpRequest<?> lifecycleRequest) {
                lifecycleRequest.addDisposalResource(() -> {
                    try {
                        completedPart.close();
                    } catch (IOException closeException) {
                        // ignore, disposal best-effort
                    }
                });
            }
            return () -> Optional.of(completedPart);
        } catch (HttpStatusException e) {
            throw e;
        } catch (Exception e) {
            context.reject(new InternalServerException("Error reading part [" + partName + "]: " + e.getMessage(), e));
            return BindingResult.EMPTY;
        }
    }

    /**
     * The servlet request under the wrappers of filters, e.g. its mutable view.
     */
    private static @Nullable ServletHttpRequest<?, ?> servletRequest(HttpRequest<?> source) {
        HttpRequest<?> current = source;
        while (true) {
            if (current instanceof ServletHttpRequest<?, ?> servletRequest) {
                return servletRequest;
            }
            if (current instanceof HttpRequestWrapper<?> wrapper) {
                current = wrapper.getDelegate();
            } else {
                return null;
            }
        }
    }

    @Override
    public Argument<CompletedPart> argumentType() {
        return Argument.of(CompletedPart.class);
    }
}
