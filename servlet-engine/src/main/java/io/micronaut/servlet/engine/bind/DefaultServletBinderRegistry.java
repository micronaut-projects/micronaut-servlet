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

import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Secondary;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.binding.DefaultServerRequestBinderRegistry;
import io.micronaut.http.server.binding.ServerBodyAnnotationBinder;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.servlet.http.ServletServerRequestBinderRegistry;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * The binders of the servlet engine: the shared binders of the server, plus the objects of the Servlet API and
 * the parts of the container.
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
@Secondary
@Replaces(DefaultServerRequestBinderRegistry.class)
@Internal
class DefaultServletBinderRegistry extends ServletServerRequestBinderRegistry {

    /**
     * Default constructor.
     *
     * @param conversionService          The conversion service
     * @param binders                    Any registered binders
     * @param bodyBinder                 The body binder
     * @param formFactory                The form factory
     * @param messageBodyHandlerRegistry The message body handler registry
     * @param configuration              The server configuration
     */
    @SuppressWarnings({"rawtypes", "java:S3740"}) // the binders of the registry of core
    DefaultServletBinderRegistry(ConversionService conversionService,
                                 List<RequestArgumentBinder> binders,
                                 ServerBodyAnnotationBinder<Object> bodyBinder,
                                 BeanProvider<FormFactory> formFactory,
                                 MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                                 HttpServerConfiguration configuration) {
        super(conversionService, binders, bodyBinder, formFactory);
        addArgumentBinder(new ServletRequestBinder());
        addArgumentBinder(new ServletResponseBinder());
        addArgumentBinder(new ServletConfigBinder());
        addArgumentBinder(new ServletContextBinder());
        addArgumentBinder(new ContainerPartBinder(new ServletPartBinder<>(conversionService, formFactory, messageBodyHandlerRegistry, configuration)));
    }

    /**
     * A {@code @Part jakarta.servlet.http.Part} argument: the part of the container itself.
     *
     * @param delegate The binder of the container parts
     */
    @SuppressWarnings("removal")
    private record ContainerPartBinder(ServletPartBinder<jakarta.servlet.http.Part> delegate)
        implements AnnotatedRequestArgumentBinder<Part, jakarta.servlet.http.Part>, TypedRequestArgumentBinder<jakarta.servlet.http.Part> {

        @Override
        public Class<Part> getAnnotationType() {
            return Part.class;
        }

        @Override
        public Argument<jakarta.servlet.http.Part> argumentType() {
            return Argument.of(jakarta.servlet.http.Part.class);
        }

        @Override
        public BindingResult<jakarta.servlet.http.Part> bind(ArgumentConversionContext<jakarta.servlet.http.Part> context, HttpRequest<?> source) {
            return delegate.bind(context, source);
        }
    }
}
