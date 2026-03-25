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

import org.jspecify.annotations.NonNull;
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
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.web.router.RouteInfo;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;
    private final DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder;

    /**
     * Default constructor.
     *
     * @param conversionService           The conversion service
     * @param messageBodyHandlerRegistry  The message body handler registry
     * @param defaultBodyAnnotationBinder The delegate default body binder
     */
    protected ServletBodyBinder(ConversionService conversionService,
                                MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                                DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder) {
        this.conversionService = conversionService;
        this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
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
            } else if (name == null) {
                @SuppressWarnings("unchecked")
                MessageBodyReader<Object> routeReader = (MessageBodyReader<Object>) source.getAttribute(HttpAttributes.ROUTE_INFO, RouteInfo.class)
                    .map(RouteInfo::getMessageBodyReader)
                    .orElse(null);
                MessageBodyReader<?> readerToUse = null;
                if (routeReader != null && routeReader.isReadable((Argument<Object>) context.getArgument(), mediaType)) {
                    readerToUse = routeReader;
                }
                if (readerToUse == null) {
                    readerToUse = messageBodyHandlerRegistry.findReader(context.getArgument(), mediaType).orElse(null);
                }
                if (readerToUse != null) {
                    return bindWithReader(readerToUse, context, source, servletHttpRequest, type, mediaType);
                }
            }
        }
        return defaultBodyAnnotationBinder.bind(context, source);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BindingResult<T> bindWithReader(MessageBodyReader<?> reader,
                                            ArgumentConversionContext<T> context,
                                            HttpRequest<?> source,
                                            ServletHttpRequest<?, ?> servletHttpRequest,
                                            Class<T> type,
                                            MediaType mediaType) {
        Argument<T> argument = context.getArgument();
        if (Publishers.isConvertibleToPublisher(type)) {
            Argument<?> elementArgument = argument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
            boolean single = Publishers.isSingle(type);
            Argument<?> listArgument = single ? null : Argument.listOf(elementArgument.getType());
            MessageBodyReader<?> elementReader = resolveReader(reader, elementArgument, mediaType);
            MessageBodyReader<?> listReader = listArgument == null ? null : resolveReader(reader, listArgument, mediaType);
            if (single && elementReader == null) {
                return BindingResult.empty();
            }
            if (!single && elementReader == null && listReader == null) {
                return BindingResult.empty();
            }
            byte[] bodyBytes;
            try (InputStream inputStream = servletHttpRequest.getInputStream()) {
                bodyBytes = inputStream.readAllBytes();
            } catch (IOException e) {
                throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
            }
            if (bodyBytes.length == 0) {
                return BindingResult.empty();
            }
            Object cacheValue;
            Publisher<?> publisher;
            if (single) {
                Object decoded = readValue(elementReader, elementArgument, mediaType, source, bodyBytes);
                if (decoded == null) {
                    return BindingResult.empty();
                }
                cacheValue = decoded;
                publisher = Publishers.just(decoded);
            } else {
                List<Object> decodedList = readList(listReader, reader, listArgument, elementReader, elementArgument, mediaType, source, bodyBytes);
                cacheValue = decodedList;
                publisher = Flux.fromIterable(decodedList);
            }
            T converted = (T) conversionService.convertRequired(publisher, type);
            if (cacheValue != null && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                parsedBody.setParsedBody(cacheValue);
            }
            return () -> Optional.ofNullable(converted);
        }
        try (InputStream inputStream = servletHttpRequest.getInputStream()) {
            Object decoded = ((MessageBodyReader<Object>) reader).read((Argument<Object>) argument, mediaType, source.getHeaders(), inputStream);
            if (decoded != null && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                parsedBody.setParsedBody(decoded);
            }
            return () -> (Optional<T>) Optional.ofNullable((T) decoded);
        } catch (CodecException | IOException e) {
            throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private MessageBodyReader<?> resolveReader(MessageBodyReader<?> primary,
                                               Argument<?> targetArgument,
                                               MediaType mediaType) {
        if (primary != null) {
            MessageBodyReader rawReader = (MessageBodyReader) primary;
            if (rawReader.isReadable((Argument) targetArgument, mediaType)) {
                return rawReader;
            }
        }
        return (MessageBodyReader<?>) messageBodyHandlerRegistry.findReader((Argument) targetArgument, mediaType).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Object readValue(MessageBodyReader<?> reader,
                             Argument<?> argument,
                             MediaType mediaType,
                             HttpRequest<?> source,
                             byte[] bodyBytes) {
        if (reader == null) {
            return null;
        }
        try (InputStream inputStream = new ByteArrayInputStream(bodyBytes)) {
            return ((MessageBodyReader<Object>) reader).read((Argument<Object>) argument, mediaType, source.getHeaders(), inputStream);
        } catch (IOException e) {
            throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Object> readList(MessageBodyReader<?> listReader,
                                  MessageBodyReader<?> primaryReader,
                                  Argument<?> listArgument,
                                  MessageBodyReader<?> elementReader,
                                  Argument<?> elementArgument,
                                  MediaType mediaType,
                                  HttpRequest<?> source,
                                  byte[] bodyBytes) {
        MessageBodyReader<?> generalListReader = listArgument == null ? null : findReaderForArgument(listArgument, mediaType);
        List<Object> rawList = tryReadList(generalListReader, listArgument, mediaType, source, bodyBytes);
        if ((rawList == null || rawList.isEmpty()) && listReader != null) {
            rawList = tryReadList(listReader, listArgument, mediaType, source, bodyBytes);
        }
        if (rawList == null || rawList.isEmpty()) {
            Argument<?> fallbackArgument = Argument.listOf(Argument.OBJECT_ARGUMENT);
            MessageBodyReader<?> fallbackReader = findReaderForArgument(fallbackArgument, mediaType);
            rawList = tryReadList(fallbackReader, fallbackArgument, mediaType, source, bodyBytes);
        }
        if (rawList == null || rawList.isEmpty()) {
            MessageBodyReader<?> objectReader = findReaderForArgument(Argument.OBJECT_ARGUMENT, mediaType);
            Object decoded = readValue(objectReader, Argument.OBJECT_ARGUMENT, mediaType, source, bodyBytes);
            rawList = toList(decoded);
        }
        if (rawList.isEmpty()) {
            return List.of();
        }
        List<Object> converted = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
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
            int length = Array.getLength(decoded);
            List<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add(Array.get(decoded, i));
            }
            return list;
        }
        return List.of(decoded);
    }

    private List<Object> tryReadList(MessageBodyReader<?> reader,
                                     Argument<?> argument,
                                     MediaType mediaType,
                                     HttpRequest<?> source,
                                     byte[] bodyBytes) {
        if (reader == null) {
            return null;
        }
        try {
            Object decoded = readValue(reader, argument, mediaType, source, bodyBytes);
            return toList(decoded);
        } catch (CodecException e) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private MessageBodyReader<?> findReaderForArgument(Argument<?> argument, MediaType mediaType) {
        return (MessageBodyReader<?>) messageBodyHandlerRegistry.findReader((Argument) argument, mediaType).orElse(null);
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
