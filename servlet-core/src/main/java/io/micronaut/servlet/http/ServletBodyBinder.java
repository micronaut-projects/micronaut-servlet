/*
 * Copyright 2017-2023 original authors
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

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionError;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.io.Readable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.DefaultBodyAnnotationBinder;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.web.router.RouteInfo;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Allows binding the body from a {@link ServletHttpRequest}.
 *
 * @param <T> The body type
 * @author graemerocher
 * @since 2.0.0
 */
public class ServletBodyBinder<T> implements AnnotatedRequestArgumentBinder<Body, T> {
    protected final ConversionService conversionService;
    private final MediaTypeCodecRegistry mediaTypeCodeRegistry;
    private final DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder;

    /**
     * Default constructor.
     *
     * @param conversionService           The conversion service
     * @param mediaTypeCodecRegistry      The codec registry
     * @param defaultBodyAnnotationBinder The delegate default body binder
     */
    protected ServletBodyBinder(ConversionService conversionService,
                                MediaTypeCodecRegistry mediaTypeCodecRegistry,
                                DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder) {
        this.conversionService = conversionService;
        this.mediaTypeCodeRegistry = mediaTypeCodecRegistry;
        this.defaultBodyAnnotationBinder = defaultBodyAnnotationBinder;
    }

    @Override
    public Class<Body> getAnnotationType() {
        return Body.class;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        final Argument<T> argument = context.getArgument();
        final Class<T> type = argument.getType();
        String name = argument.getAnnotationMetadata().stringValue(Body.class).orElse(null);
        if (source instanceof ServletHttpRequest<?, ?> servletHttpRequest) {
            if (Readable.class.isAssignableFrom(type)) {
                Readable readable = new ServletReadable(servletHttpRequest);
                return () -> (Optional<T>) Optional.of(readable);
            }
            if (CharSequence.class.isAssignableFrom(type) && name == null) {
                try (BufferedReader bufferedReader = servletHttpRequest.getReader()) {
                    String text = IOUtils.readText(bufferedReader);
                    return () -> (Optional<T>) Optional.of(text);
                } catch (IOException e) {
                    return new BindingResult<T>() {
                        @Override
                        public Optional<T> getValue() {
                            return Optional.empty();
                        }

                        @Override
                        public List<ConversionError> getConversionErrors() {
                            return Collections.singletonList(
                                    () -> e
                            );
                        }
                    };
                }
            }
            final MediaType mediaType = source.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
            if (isFormSubmission(mediaType)) {
                if (name != null) {
                    return () -> servletHttpRequest.getParameters().get(name, context);
                } else {
                    Optional<T> result = conversionService.convert(servletHttpRequest.getParameters().asMap(), context);
                    return () -> result;
                }
            } else {
                MessageBodyReader messageBodyReader = source.getAttribute(HttpAttributes.ROUTE_INFO, RouteInfo.class)
                    .map(RouteInfo::getMessageBodyReader)
                    .orElse(null);
                if (name == null && messageBodyReader != null && messageBodyReader.isReadable(context.getArgument(), mediaType)) {
                    try (InputStream inputStream = servletHttpRequest.getInputStream()) {
                        Object content;
                        if (Publishers.isConvertibleToPublisher(context.getArgument().getType())) {
                            Argument<?> firstArg = context.getArgument().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
                            Publisher<?> publisher;
                            if (Publishers.isSingle(context.getArgument().getType())) {
                                publisher = Publishers.just(messageBodyReader.read(firstArg, mediaType, source.getHeaders(), inputStream));
                            } else {
                                publisher = Flux.fromIterable((Iterable<?>) messageBodyReader.read(Argument.listOf(firstArg), mediaType, source.getHeaders(), inputStream));
                            }
                            content = conversionService.convertRequired(publisher, type);
                        } else {
                            content = messageBodyReader.read(context.getArgument(), mediaType, source.getHeaders(), inputStream);
                        }
                        if (content != null && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                            parsedBody.setParsedBody(content);
                        }
                        return () -> (Optional<T>) Optional.ofNullable(content);
                    } catch (CodecException | IOException e) {
                        throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
                    }
                } else {

                    final MediaTypeCodec codec = mediaTypeCodeRegistry
                        .findCodec(mediaType, type)
                        .orElse(null);

                    if (codec != null) {
                        try (InputStream inputStream = servletHttpRequest.getInputStream()) {
                            if (Publishers.isConvertibleToPublisher(type)) {
                                final Argument<?> typeArg = argument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
                                if (Publishers.isSingle(type)) {
                                    T content = (T) codec.decode(typeArg, inputStream);
                                    final Publisher<T> publisher = Publishers.just(content);
                                    final T converted = conversionService.convertRequired(publisher, type);
                                    return () -> Optional.of(converted);
                                }
                                final Argument<? extends List<?>> containerType = Argument.listOf(typeArg.getType());
                                T content = (T) codec.decode(containerType, inputStream);
                                final Publisher<T> publisher = Flux.fromIterable((Iterable) content);
                                final T converted = conversionService.convertRequired(publisher, type);
                                return () -> Optional.of(converted);
                            }
                            if (type.isAssignableFrom(byte[].class)) {
                                byte[] content = inputStream.readAllBytes();
                                return () -> Optional.of((T) content);
                            } else if (type.isArray()) {
                                Class<?> componentType = type.getComponentType();
                                List<T> content = (List<T>) codec.decode(Argument.listOf(componentType), inputStream);
                                Object[] array = content.toArray((Object[]) Array.newInstance(componentType, 0));
                                return () -> Optional.of((T) array);
                            }
                            T content;
                            if (name != null) {
                                var decode = codec.decode(Map.class, inputStream);
                                content = conversionService.convert(decode.get(name), argument).orElse(null);
                            } else {
                                content = codec.decode(argument, inputStream);
                            }
                            if (content != null && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                                parsedBody.setParsedBody(content);
                            }
                            return () -> Optional.of(content);
                        } catch (CodecException | IOException e) {
                            throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
                        }
                    }
                }
            }

        }
        return defaultBodyAnnotationBinder.bind(context, source);
    }

    private boolean isFormSubmission(MediaType contentType) {
        return MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(contentType) || MediaType.MULTIPART_FORM_DATA_TYPE.equals(contentType);
    }

    private static final class ServletReadable implements Readable {
        private final ServletHttpRequest<?, ?> servletHttpRequest;

        public ServletReadable(ServletHttpRequest<?, ?> servletHttpRequest) {
            this.servletHttpRequest = servletHttpRequest;
        }

        @Override
        public Reader asReader() throws IOException {
            return servletHttpRequest.getReader();
        }

        @NonNull
        @Override
        public InputStream asInputStream() throws IOException {
            return servletHttpRequest.getInputStream();
        }

        @Override
        public boolean exists() {
            return true;
        }

        @NonNull
        @Override
        public String getName() {
            return servletHttpRequest.getPath();
        }
    }
}
