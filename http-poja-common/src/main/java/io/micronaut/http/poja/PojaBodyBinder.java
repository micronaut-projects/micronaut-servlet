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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionError;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.DefaultBodyAnnotationBinder;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.json.codec.MapperMediaTypeCodec;
import io.micronaut.json.tree.JsonNode;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A body binder implementation for serverless POJA applications.
 *
 * @param <T> The body type
 */
@Internal
final class PojaBodyBinder<T> implements AnnotatedRequestArgumentBinder<Body, T> {
    private static final Logger LOG = LoggerFactory.getLogger(PojaBodyBinder.class);
    private final MediaTypeCodecRegistry mediaTypeCodeRegistry;
    private final DefaultBodyAnnotationBinder<T> defaultBodyBinder;
    private final ConversionService conversionService;

    /**
     * Default constructor.
     *
     * @param conversionService      The conversion service
     * @param mediaTypeCodecRegistry The codec registry
     */
    protected PojaBodyBinder(
            ConversionService conversionService,
            MediaTypeCodecRegistry mediaTypeCodecRegistry,
            DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder) {
        this.defaultBodyBinder = defaultBodyAnnotationBinder;
        this.mediaTypeCodeRegistry = mediaTypeCodecRegistry;
        this.conversionService = conversionService;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        final Argument<T> argument = context.getArgument();
        final Class<T> type = argument.getType();
        String name = argument.getAnnotationMetadata().stringValue(Body.class).orElse(null);

        if (source instanceof PojaHttpRequest<?, ?, ?> pojaHttpRequest) {
            if (CharSequence.class.isAssignableFrom(type) && name == null) {
                return (BindingResult<T>) bindCharSequence(pojaHttpRequest, source);
            } else if (argument.getType().isAssignableFrom(byte[].class) && name == null) {
                return (BindingResult<T>) bindByteArray(pojaHttpRequest);
            } else {
                final MediaType mediaType = source.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
                if (pojaHttpRequest.isFormSubmission()) {
                    return bindFormData(pojaHttpRequest, name, context);
                }

                final MediaTypeCodec codec = mediaTypeCodeRegistry
                        .findCodec(mediaType, type)
                        .orElse(null);
                if (codec != null) {
                   return bindWithCodec(pojaHttpRequest, source, codec, argument, type, name);
                }
            }
        }
        LOG.trace("Not a function request, falling back to default body decoding");
        return defaultBodyBinder.bind(context, source);
    }

    private BindingResult<T> bindWithCodec(
            PojaHttpRequest<?, ?, ?> pojaHttpRequest, HttpRequest<?> source, MediaTypeCodec codec,
            Argument<T> argument, Class<T> type, String name
    ) {
        LOG.trace("Decoding function body with codec: {}", codec.getClass().getSimpleName());
        return pojaHttpRequest.consumeBody(inputStream -> {
            try {
                if (Publishers.isConvertibleToPublisher(type)) {
                    return bindPublisher(argument, type, codec, inputStream);
                } else {
                    return bindPojo(argument, type, codec, inputStream, name);
                }
            } catch (CodecException e) {
                LOG.trace("Error occurred decoding function body: {}", e.getMessage(), e);
                return new ConversionFailedBindingResult<>(e);
            }
        });
    }

    private BindingResult<CharSequence> bindCharSequence(PojaHttpRequest<?, ?, ?> pojaHttpRequest, HttpRequest<?> source) {
        return pojaHttpRequest.consumeBody(inputStream -> {
            try {
                String content = IOUtils.readText(new BufferedReader(new InputStreamReader(
                    inputStream, source.getCharacterEncoding()
                )));
                LOG.trace("Read content of length {} from function body", content.length());
                return () -> Optional.of(content);
            } catch (IOException e) {
                LOG.debug("Error occurred reading function body: {}", e.getMessage(), e);
                return new ConversionFailedBindingResult<>(e);
            }
        });
    }

    private BindingResult<byte[]> bindByteArray(PojaHttpRequest<?, ?, ?> pojaHttpRequest) {
        return pojaHttpRequest.consumeBody(inputStream -> {
            try {
                byte[] bytes = inputStream.readAllBytes();
                return () -> Optional.of(bytes);
            } catch (IOException e) {
                LOG.debug("Error occurred reading function body: {}", e.getMessage(), e);
                return new ConversionFailedBindingResult<>(e);
            }
        });
    }

    private BindingResult<T> bindFormData(
        PojaHttpRequest<?, ?, ?> servletHttpRequest, String name, ArgumentConversionContext<T> context
    ) {
        Optional<ConvertibleValues> form = servletHttpRequest.getBody(PojaHttpRequest.CONVERTIBLE_VALUES_ARGUMENT);
        if (form.isEmpty()) {
            return BindingResult.empty();
        }
        if (name != null) {
            return () -> form.get().get(name, context);
        }
        return () -> conversionService.convert(form.get().asMap(), context);
    }

    private BindingResult<T> bindPojo(
        Argument<T> argument, Class<?> type, MediaTypeCodec codec, InputStream inputStream, String name
    ) {
        Argument<?> requiredArg = type.isArray() ? Argument.listOf(type.getComponentType()) : argument;
        Object converted;

        if (name != null && codec instanceof MapperMediaTypeCodec jsonCodec) {
            // Special case where a particular part of body is required
            try {
                JsonNode node = jsonCodec.getJsonMapper()
                    .readValue(inputStream, JsonNode.class);
                JsonNode field = node.get(name);
                if (field == null) {
                    return Optional::empty;
                }
                converted = jsonCodec.decode(requiredArg, field);
            } catch (IOException e) {
                throw new CodecException("Error decoding JSON stream for type [JsonNode]: " + e.getMessage(), e);
            }
        } else {
            converted = codec.decode(argument, inputStream);
        }

        if (type.isArray()) {
            converted = ((List<?>) converted).toArray((Object[]) Array.newInstance(type.getComponentType(), 0));
        }
        T content = (T) converted;
        LOG.trace("Decoded object from function body: {}", converted);
        return () -> Optional.of(content);
    }

    private BindingResult<T> bindPublisher(
        Argument<T> argument, Class<T> type, MediaTypeCodec codec, InputStream inputStream
    ) {
        final Argument<?> typeArg = argument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
        if (Publishers.isSingle(type)) {
            T content = (T) codec.decode(typeArg, inputStream);
            final Publisher<T> publisher = Publishers.just(content);
            LOG.trace("Decoded single publisher from function body: {}", content);
            final T converted = conversionService.convertRequired(publisher, type);
            return () -> Optional.of(converted);
        } else {
            final Argument<? extends List<?>> containerType = Argument.listOf(typeArg.getType());
            if (codec instanceof MapperMediaTypeCodec jsonCodec) {
                // Special JSON case: we can accept both array and a single value
                try {
                    JsonNode node = jsonCodec.getJsonMapper()
                        .readValue(inputStream, JsonNode.class);
                    T converted;
                    if (node.isArray()) {
                        converted = Publishers.convertPublisher(
                            conversionService,
                            Flux.fromIterable(node.values())
                                .map(itemNode -> jsonCodec.decode(typeArg, itemNode)),
                            type
                        );
                    } else {
                        converted = Publishers.convertPublisher(
                            conversionService,
                            Mono.just(jsonCodec.decode(typeArg, node)),
                            type
                        );
                    }
                    return () -> Optional.of(converted);
                } catch (IOException e) {
                    throw new CodecException("Error decoding JSON stream for type [JsonNode]: " + e.getMessage(), e);
                }
            }
            T content = (T) codec.decode(containerType, inputStream);
            LOG.trace("Decoded flux publisher from function body: {}", content);
            final Flux flowable = Flux.fromIterable((Iterable) content);
            final T converted = conversionService.convertRequired(flowable, type);
            return () -> Optional.of(converted);
        }
    }

    @Override
    public Class<Body> getAnnotationType() {
        return Body.class;
    }

    /**
     * A binding result implementation for the case when conversion error was thrown.
     *
     * @param <T> The type to be bound
     * @param e The conversion error
     */
    private record ConversionFailedBindingResult<T>(
        Exception e
    ) implements BindingResult<T> {

        @Override
        public Optional<T> getValue() {
            return Optional.empty();
        }

        @Override
        public List<ConversionError> getConversionErrors() {
            return Collections.singletonList(() -> e);
        }

    }

}
