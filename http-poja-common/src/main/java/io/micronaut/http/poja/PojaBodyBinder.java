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
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.json.tree.JsonNode;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;
    private final DefaultBodyAnnotationBinder<T> defaultBodyBinder;
    private final ConversionService conversionService;

    /**
     * Default constructor.
     *
     * @param conversionService      The conversion service
     * @param messageBodyHandlerRegistry The message body handler registry
     */
    protected PojaBodyBinder(
            ConversionService conversionService,
            MessageBodyHandlerRegistry messageBodyHandlerRegistry,
            DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder) {
        this.defaultBodyBinder = defaultBodyAnnotationBinder;
        this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
        this.conversionService = conversionService;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        final Argument<T> argument = context.getArgument();
        final Class<T> type = argument.getType();
        String name = argument.getAnnotationMetadata().stringValue(Body.class).orElse(null);

        if (source instanceof PojaHttpRequest<?, ?, ?> pojaHttpRequest) {
            if ((type == CharSequence.class || type == String.class) && name == null) {
                return (BindingResult<T>) bindCharSequence(pojaHttpRequest, source);
            } else if (type == byte[].class && name == null) {
                return (BindingResult<T>) bindByteArray(pojaHttpRequest);
            } else {
                final MediaType mediaType = source.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
                if (pojaHttpRequest.isFormSubmission()) {
                    return bindFormData(pojaHttpRequest, name, context);
                }

                MessageBodyReader<?> reader = messageBodyHandlerRegistry.findReader(argument, mediaType).orElse(null);
                if (name == null && reader != null) {
                    return bindWithReader(pojaHttpRequest, source, (MessageBodyReader<?>) reader, argument, type, mediaType);
                }
                if (name != null) {
                    BindingResult<T> namedResult = bindNamedField(pojaHttpRequest, source, argument, mediaType, name);
                    if (namedResult != null) {
                        return namedResult;
                    }
                }
                if (reader != null) {
                    return bindWithReader(pojaHttpRequest, source, (MessageBodyReader<?>) reader, argument, type, mediaType);
                }
            }
        }
        LOG.trace("Not a function request, falling back to default body decoding");
        return defaultBodyBinder.bind(context, source);
    }

    private BindingResult<T> bindWithReader(
        PojaHttpRequest<?, ?, ?> pojaHttpRequest,
        HttpRequest<?> source,
        MessageBodyReader<?> reader,
        Argument<T> argument,
        Class<T> type,
        MediaType mediaType
    ) {
        if (Publishers.isConvertibleToPublisher(type)) {
            Argument<?> elementArgument = argument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
            return pojaHttpRequest.consumeBody(inputStream -> {
                try {
                    if (Publishers.isSingle(type)) {
                        Object decoded = ((MessageBodyReader<Object>) reader).read((Argument<Object>) elementArgument, mediaType, source.getHeaders(), inputStream);
                        if (decoded == null) {
                            return BindingResult.empty();
                        }
                        Publisher<?> publisher = Publishers.just(decoded);
                        T converted = conversionService.convertRequired(publisher, type);
                        return () -> Optional.ofNullable(converted);
                    }
                    @SuppressWarnings("unchecked")
                    Argument<?> listArgument = Argument.listOf(elementArgument.getType());
                    Object decoded = ((MessageBodyReader<Object>) reader)
                        .read((Argument<Object>) listArgument, mediaType, source.getHeaders(), inputStream);
                    Iterable<?> iterable = decoded instanceof Iterable ? (Iterable<?>) decoded :
                        decoded == null ? Collections.emptyList() : Collections.singletonList(decoded);
                    Publisher<?> publisher = Flux.fromIterable(iterable);
                    T converted = conversionService.convertRequired(publisher, type);
                    return () -> Optional.ofNullable(converted);
                } catch (CodecException e) {
                    throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
                }
            });
        }
        return pojaHttpRequest.consumeBody(inputStream -> {
            try {
                Object decoded = ((MessageBodyReader<Object>) reader).read((Argument<Object>) argument, mediaType, source.getHeaders(), inputStream);
                return () -> (Optional<T>) Optional.ofNullable((T) decoded);
            } catch (CodecException e) {
                throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
            }
        });
    }

    private BindingResult<T> bindNamedField(
        PojaHttpRequest<?, ?, ?> pojaHttpRequest,
        HttpRequest<?> source,
        Argument<T> argument,
        MediaType mediaType,
        String name
    ) {
        Argument<java.util.Map<String, Object>> mapArg = Argument.mapOf(String.class, Object.class);
        @SuppressWarnings("unchecked")
        MessageBodyReader<java.util.Map<String, Object>> mapReader =
            (MessageBodyReader<java.util.Map<String, Object>>) messageBodyHandlerRegistry.findReader(mapArg, mediaType).orElse(null);
        if (mapReader != null) {
            return pojaHttpRequest.consumeBody(inputStream -> {
                try {
                    java.util.Map<String, Object> map = mapReader.read(mapArg, mediaType, source.getHeaders(), inputStream);
                    if (map == null || !map.containsKey(name)) {
                        return BindingResult.empty();
                    }
                    Object value = map.get(name);
                    return () -> conversionService.convert(value, argument);
                } catch (CodecException e) {
                    throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
                }
            });
        }
        if (MediaType.APPLICATION_JSON_TYPE.equals(mediaType)) {
            Argument<JsonNode> jsonNodeArgument = Argument.of(JsonNode.class);
            MessageBodyReader<JsonNode> jsonReader = messageBodyHandlerRegistry.findReader(jsonNodeArgument, mediaType).orElse(null);
            if (jsonReader != null) {
                return pojaHttpRequest.consumeBody(inputStream -> {
                    try {
                        JsonNode root = jsonReader.read(jsonNodeArgument, mediaType, source.getHeaders(), inputStream);
                        if (root == null) {
                            return BindingResult.empty();
                        }
                        JsonNode field = root.get(name);
                        if (field == null || field.isNull()) {
                            return BindingResult.empty();
                        }
                        return () -> conversionService.convert(field, argument);
                    } catch (CodecException e) {
                        throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
                    }
                });
            }
        }
        return null;
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
