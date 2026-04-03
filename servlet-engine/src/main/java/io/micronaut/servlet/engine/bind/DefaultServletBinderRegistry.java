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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.DefaultRequestBinderRegistry;
import io.micronaut.http.bind.binders.DefaultBodyAnnotationBinder;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.json.JsonMapper;
import io.micronaut.servlet.http.ServletBinderRegistry;
import io.micronaut.servlet.http.ServletBodyBinder;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

/**
 * Replaces the {@link DefaultRequestBinderRegistry} with one capable of binding from servlet requests.
 *
 * @param <T> The type
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
@Replaces(DefaultRequestBinderRegistry.class)
@Internal
class DefaultServletBinderRegistry<T> extends ServletBinderRegistry<T> {

    public static final Argument<byte[]> BYTE_ARRAY = Argument.of(byte[].class);

    /**
     * Default constructor.
     *
     * @param messageBodyHandlerRegistry  The message body handler registry
     * @param conversionService           The conversion service
     * @param binders                     Any registered binders
     * @param defaultBodyAnnotationBinder The delegate default body binder
     * @param jsonMapper                  The JSON mapper
     */
    public DefaultServletBinderRegistry(MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                                        ConversionService conversionService,
                                        List<RequestArgumentBinder> binders,
                                        DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder,
                                        BeanProvider<FormFactory> formFactoryProvider,
                                        JsonMapper jsonMapper) {
        super(messageBodyHandlerRegistry, conversionService, binders, defaultBodyAnnotationBinder, jsonMapper);
        byType.put(HttpServletRequest.class, new ServletRequestBinder());
        byType.put(HttpServletResponse.class, new ServletResponseBinder());
        byType.put(ServletConfig.class, new ServletConfigBinder());
        byType.put(ServletContext.class, new ServletContextBinder());
        byType.put(CompletedPart.class, new CompletedPartRequestArgumentBinder());
        byAnnotation.put(Part.class, new ServletPartBinder<>(conversionService, formFactoryProvider));
    }

    @Override
    protected ServletBodyBinder<T> newServletBodyBinder(MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                                                        ConversionService conversionService,
                                                        DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder,
                                                        JsonMapper jsonMapper) {
        return new DefaultServletBodyBinder<>(conversionService, messageBodyHandlerRegistry, defaultBodyAnnotationBinder, jsonMapper);
    }

    /**
     * Overridden body binder.
     *
     * @param <T> The type
     */
    private static class DefaultServletBodyBinder<T> extends ServletBodyBinder<T> {

        /**
         * Default constructor.
         *
         * @param conversionService           The conversion service
         * @param messageBodyHandlerRegistry  The message body handler registry
         * @param defaultBodyAnnotationBinder The delegate default body binder
         * @param jsonMapper                  The json mapper
         */
        protected DefaultServletBodyBinder(ConversionService conversionService, MessageBodyHandlerRegistry messageBodyHandlerRegistry, DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder, JsonMapper jsonMapper) {
            super(conversionService, messageBodyHandlerRegistry, defaultBodyAnnotationBinder, jsonMapper);
        }

        @Override
        public BindingResult bind(ArgumentConversionContext context, HttpRequest source) {
            Argument<?> argument = context.getArgument();
            Class<?> type = argument.getType();
            if (CompletedPart.class.isAssignableFrom(type)) {
                return new CompletedPartRequestArgumentBinder().bind(context, source);
            }
            return super.bind(context, source);
        }
    }
}
