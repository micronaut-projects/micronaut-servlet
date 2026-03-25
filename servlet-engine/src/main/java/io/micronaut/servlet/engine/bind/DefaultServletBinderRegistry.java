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

import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.async.publisher.Publishers;
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
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.servlet.http.ServletBinderRegistry;
import io.micronaut.servlet.http.ServletBodyBinder;
import io.micronaut.servlet.http.ParsedBodyHolder;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

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
            DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder
    ) {
        super(messageBodyHandlerRegistry, conversionService, binders, defaultBodyAnnotationBinder);
        byType.put(HttpServletRequest.class, new ServletRequestBinder());
        byType.put(HttpServletResponse.class, new ServletResponseBinder());
        byType.put(ServletConfig.class, new ServletConfigBinder());
        byType.put(ServletContext.class, new ServletContextBinder());
        byType.put(CompletedPart.class, new CompletedPartRequestArgumentBinder());
        byAnnotation.put(Part.class, new ServletPartBinder<>(messageBodyHandlerRegistry, conversionService));
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
                    future = serverRequest.byteBody().buffer().thenApply(body -> {
                        try (var available = body) {
                            return available.toString(characterEncoding);
                        }
                    });
                } else if (byte[].class.isAssignableFrom(javaArgument)) {
                    future = serverRequest.byteBody().buffer().thenApply(body -> {
                        try (var available = body) {
                            return available.toByteArray();
                        }
                    });
                } else {
                    MediaType mediaType = serverRequest.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
                    @SuppressWarnings("unchecked")
                    MessageBodyReader<Object> reader = (MessageBodyReader<Object>) messageBodyHandlerRegistry
                        .findReader((Argument<Object>) typeArgument, mediaType)
                        .orElse(null);
                    if (reader == null) {
                        return super.bind(context, source);
                    } else {
                        Supplier<CompletableFuture<?>> bufferedRead = () -> serverRequest.byteBody().buffer().thenApply(body -> {
                            try (var available = body) {
                                InputStream inputStream = available.toInputStream();
                                return reader.read((Argument<Object>) typeArgument, mediaType, source.getHeaders(), inputStream);
                            }
                        });
                        future = bufferedRead.get();
                    }
                }
                CompletableFuture<?> finalFuture = future;
                return () -> Optional.of(finalFuture);
            } else if (CompletedPart.class.isAssignableFrom(type)) {
                return new CompletedPartRequestArgumentBinder().bind(context, source);
            } else {
                if (Publishers.isConvertibleToPublisher(type)) {
                    Argument<?> typeArgument = argument.getFirstTypeVariable().orElse(BYTE_ARRAY);
                    Class<?> javaArgument = typeArgument.getType();

                    ServerHttpRequest<?> serverRequest = (ServerHttpRequest<?>) source;
                    Charset characterEncoding = serverRequest.getCharacterEncoding();
                    boolean singlePublisher = Publishers.isSingle(type) || argument.isSpecifiedSingle();
                    if (CharSequence.class.isAssignableFrom(javaArgument)) {
                        Flux<String> stringFlux = Flux.from(serverRequest.byteBody().toByteArrayPublisher())
                                .map(bytes -> new String(bytes, characterEncoding));
                        if (type.isInstance(stringFlux)) {
                            return () -> Optional.of(stringFlux);
                        } else {
                            Object converted = Publishers.convertPublisher(conversionService, stringFlux, type);
                            return () -> Optional.of(converted);
                        }
                    } else if (byte[].class.isAssignableFrom(javaArgument)) {
                        Object converted = Publishers.convertPublisher(conversionService, Flux.from(serverRequest.byteBody().toByteArrayPublisher()), type);
                        return () -> Optional.of(converted);
                    } else {
                        MediaType mediaType = serverRequest.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
                        if (singlePublisher) {
                            MessageBodyReader<?> reader = findReaderForArgument(typeArgument, mediaType);
                            if (reader != null) {
                                Publisher<?> publisher = Mono.fromFuture(
                                    serverRequest.byteBody().buffer().thenApply(body -> {
                                        try (var available = body) {
                                            InputStream inputStream = available.toInputStream();
                                            return ((MessageBodyReader<Object>) reader).read((Argument<Object>) typeArgument, mediaType, source.getHeaders(), inputStream);
                                        }
                                    })
                                );
                                Object converted = Publishers.convertPublisher(conversionService, publisher, type);
                                return () -> Optional.of(converted);
                            }
                        } else {
                            Publisher<?> publisher = Mono.fromFuture(
                                serverRequest.byteBody().buffer().thenApply(body -> {
                                    try (var available = body) {
                                        byte[] bytes = available.toByteArray();
                                        List<Object> decoded = decodeListFromBytes(typeArgument, mediaType, source, bytes);
                                        if (source instanceof ParsedBodyHolder<?> parsedBodyHolder) {
                                            //noinspection unchecked
                                            ((ParsedBodyHolder<Object>) parsedBodyHolder).setParsedBody(decoded);
                                        }
                                        return decoded;
                                    }
                                })
                            ).flatMapMany(list -> Flux.fromIterable(list));
                            Object converted = Publishers.convertPublisher(conversionService, publisher, type);
                            return () -> Optional.of(converted);
                        }
                    }
                }
            }
            return super.bind(context, source);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private List<Object> decodeListFromBytes(Argument<?> elementArgument,
                                                 MediaType mediaType,
                                                 HttpRequest<?> source,
                                                 byte[] bodyBytes) {
            Argument<?> listArgument = Argument.listOf(elementArgument.getType());
            MessageBodyReader<?> listReader = findReaderForArgument(listArgument, mediaType);
            if (listReader != null) {
                ByteArrayInputStream is = new ByteArrayInputStream(bodyBytes);
                Object decoded = ((MessageBodyReader<Object>) listReader).read((Argument<Object>) listArgument, mediaType, source.getHeaders(), is);
                return convertElements(toList(decoded), elementArgument);
            }
            Argument<List<Object>> objectListArgument = (Argument<List<Object>>) (Argument) Argument.listOf(Argument.OBJECT_ARGUMENT);
            MessageBodyReader<List<Object>> fallbackReader = (MessageBodyReader<List<Object>>) findReaderForArgument(objectListArgument, mediaType);
            if (fallbackReader != null) {
                ByteArrayInputStream is = new ByteArrayInputStream(bodyBytes);
                List<Object> decoded = fallbackReader.read(objectListArgument, mediaType, source.getHeaders(), is);
                return convertElements(toList(decoded), elementArgument);
            }
            MessageBodyReader<?> objectReader = findReaderForArgument(Argument.OBJECT_ARGUMENT, mediaType);
            if (objectReader != null) {
                ByteArrayInputStream is = new ByteArrayInputStream(bodyBytes);
                Object decoded = ((MessageBodyReader<Object>) objectReader).read(Argument.OBJECT_ARGUMENT, mediaType, source.getHeaders(), is);
                return convertElements(toList(decoded), elementArgument);
            }
            return List.of();
        }

        private List<Object> convertElements(List<Object> raw, Argument<?> elementArgument) {
            if (raw.isEmpty()) {
                return raw;
            }
            List<Object> converted = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item != null && elementArgument.getType().isInstance(item)) {
                    converted.add(item);
                } else {
                    Object convertedItem = conversionService.convert(item, (Argument<Object>) elementArgument)
                        .orElseThrow(() -> new CodecException("Unable to convert list element [" + item + "] to type: " + elementArgument.getType()));
                    converted.add(convertedItem);
                }
            }
            return converted;
        }

        private List<Object> toList(Object decoded) {
            if (decoded == null) {
                return List.of();
            }
            if (decoded instanceof List<?> list) {
                return new ArrayList<>(list);
            }
            if (decoded instanceof Iterable<?> iterable) {
                List<Object> list = new ArrayList<>();
                iterable.forEach(list::add);
                return list;
            }
            if (decoded.getClass().isArray()) {
                int length = java.lang.reflect.Array.getLength(decoded);
                List<Object> list = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    list.add(java.lang.reflect.Array.get(decoded, i));
                }
                return list;
            }
            List<Object> single = new ArrayList<>(1);
            single.add(decoded);
            return single;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private MessageBodyReader<?> findReaderForArgument(Argument<?> argument, MediaType mediaType) {
            return (MessageBodyReader<?>) messageBodyHandlerRegistry.findReader((Argument) argument, mediaType).orElse(null);
        }
    }
}
