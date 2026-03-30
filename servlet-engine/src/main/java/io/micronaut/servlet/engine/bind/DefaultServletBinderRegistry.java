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
import io.micronaut.http.MediaType;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.DefaultRequestBinderRegistry;
import io.micronaut.http.bind.binders.DefaultBodyAnnotationBinder;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.body.AvailableByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.servlet.http.ServletBinderRegistry;
import io.micronaut.servlet.http.ServletBodyBinder;
import io.micronaut.http.server.multipart.FormFactory;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.reactivestreams.Processor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Replaces the {@link DefaultRequestBinderRegistry} with one capable of binding from servlet requests.
 *
 * @param <T> The type
 *
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
     * @param messageBodyHandlerRegistry The message body handler registry
     * @param conversionService      The conversion service
     * @param binders                Any registered binders
     */
    public DefaultServletBinderRegistry(
            MessageBodyHandlerRegistry messageBodyHandlerRegistry,
            ConversionService conversionService,
            List<RequestArgumentBinder> binders,
            DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder,
            BeanProvider<FormFactory> formFactoryProvider
    ) {
        super(messageBodyHandlerRegistry, conversionService, binders, defaultBodyAnnotationBinder);
        byType.put(HttpServletRequest.class, new ServletRequestBinder());
        byType.put(HttpServletResponse.class, new ServletResponseBinder());
        byType.put(ServletConfig.class, new ServletConfigBinder());
        byType.put(ServletContext.class, new ServletContextBinder());
        byType.put(CompletedPart.class, new CompletedPartRequestArgumentBinder());
        byAnnotation.put(Part.class, new ServletPartBinder<>(conversionService, formFactoryProvider));
    }

    @SuppressWarnings("unchecked")
    @Override
    protected ServletBodyBinder<T> newServletBodyBinder(
        MessageBodyHandlerRegistry messageBodyHandlerRegistry,
        ConversionService conversionService,
        DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder) {
        return new DefaultServletBodyBinder<>(conversionService, messageBodyHandlerRegistry, defaultBodyAnnotationBinder);
    }

    /**
     * Overridden body binder.
     *
     * @param <T> The type
     */
    private static class DefaultServletBodyBinder<T> extends ServletBodyBinder<T> {
        private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;

        public DefaultServletBodyBinder(ConversionService conversionService,
                                        MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                                        DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder) {
            super(conversionService, messageBodyHandlerRegistry, defaultBodyAnnotationBinder);
            this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
        }

        @Override
        public BindingResult bind(ArgumentConversionContext context, HttpRequest source) {
            Argument<?> argument = context.getArgument();
            Class<?> type = argument.getType();
            if (CompletionStage.class.isAssignableFrom(type)) {
                ServerHttpRequest<?> serverRequest = (ServerHttpRequest<?>) source;
                CompletableFuture<?> future;
                Argument<?> typeArgument = argument.getFirstTypeVariable().orElse(BYTE_ARRAY);
                Class<?> javaArgument = typeArgument.getType();
                Charset characterEncoding = source.getCharacterEncoding();
                if (CharSequence.class.isAssignableFrom(javaArgument)) {
                    future = serverRequest.byteBody().buffer().thenApply(bb -> bb.toString(characterEncoding));
                } else if (byte[].class.isAssignableFrom(javaArgument)) {
                    future = serverRequest.byteBody().buffer().thenApply(AvailableByteBody::toByteArray);
                } else {
                    MediaType mediaType = serverRequest.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
                    MessageBodyReader<Object> reader = findReader(typeArgument, mediaType);
                    if (reader == null) {
                        return super.bind(context, source);
                    }
                    future = serverRequest.byteBody().buffer().thenApply(bb -> {
                        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bb.toByteArray())) {
                            return reader.read((Argument<Object>) typeArgument, mediaType, source.getHeaders(), inputStream);
                        } catch (IOException e) {
                            throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
                        }
                    });
                }
                return () -> Optional.of(future);
            } else if (CompletedPart.class.isAssignableFrom(type)) {
                return new CompletedPartRequestArgumentBinder().bind(context, source);
            }
            return super.bind(context, source);
        }

        @SuppressWarnings("unchecked")
        private MessageBodyReader<Object> findReader(Argument<?> argument, MediaType mediaType) {
            return (MessageBodyReader<Object>) messageBodyHandlerRegistry
                .findReader((Argument) argument, mediaType)
                .orElse(null);
        }
    }
}
