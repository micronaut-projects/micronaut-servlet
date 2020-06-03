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

import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.bind.DefaultRequestBinderRegistry;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.codec.MediaTypeCodecRegistry;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link RequestBinderRegistry} implementation specifically for Serverless functions over HTTP.
 *
 * @author graemerocher
 * @since 2.0.0
 */
public abstract class ServletBinderRegistry implements RequestBinderRegistry {

    private static final String BINDABLE_ANN = Bindable.class.getName();
    protected final Map<Class<? extends Annotation>, RequestArgumentBinder> byAnnotation = new LinkedHashMap<>(5);
    protected final Map<Class<?>, RequestArgumentBinder> byType = new LinkedHashMap<>(5);
    private final DefaultRequestBinderRegistry defaultRegistry;

    /**
     * Default constructor.
     *
     * @param mediaTypeCodecRegistry The media type codec registry
     * @param conversionService      The conversion service
     * @param binders                Any registered binders
     */
    public ServletBinderRegistry(
            MediaTypeCodecRegistry mediaTypeCodecRegistry,
            ConversionService conversionService,
            List<RequestArgumentBinder> binders) {
        this.defaultRegistry = new DefaultRequestBinderRegistry(conversionService, binders);
        this.byAnnotation.put(Body.class, newServletBodyBinder(mediaTypeCodecRegistry, conversionService));
        this.byType.put(HttpRequest.class, new ServletRequestBinder(mediaTypeCodecRegistry));
    }

    /**
     * Creates the servlet body binder.
     * @param mediaTypeCodecRegistry The media type registry
     * @param conversionService The conversion service
     * @return The servlet body body
     */
    protected ServletBodyBinder newServletBodyBinder(
            MediaTypeCodecRegistry mediaTypeCodecRegistry,
            ConversionService conversionService) {
        return new ServletBodyBinder(conversionService, mediaTypeCodecRegistry);
    }

    @Override
    public <T> Optional<ArgumentBinder<T, HttpRequest<?>>> findArgumentBinder(Argument<T> argument, HttpRequest<?> source) {
        final Class<? extends Annotation> annotation = argument.getAnnotationMetadata().getAnnotationTypeByStereotype(BINDABLE_ANN).orElse(null);
        if (annotation != null) {
            final RequestArgumentBinder binder = byAnnotation.get(annotation);
            if (binder != null) {
                return Optional.of(binder);
            }
        }

        final RequestArgumentBinder requestArgumentBinder = byType.get(argument.getType());
        if (requestArgumentBinder != null) {
            return Optional.of(requestArgumentBinder);
        } else {
            return this.defaultRegistry.findArgumentBinder(argument, source);
        }
    }
}
