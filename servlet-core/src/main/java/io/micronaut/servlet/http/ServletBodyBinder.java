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

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionError;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.io.Readable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.DefaultBodyAnnotationBinder;
import io.micronaut.http.body.AvailableByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Allows binding the body from a {@link ServletHttpRequest}.
 *
 * @param <T> The body type
 * @author graemerocher
 * @since 2.0.0
 */
public class ServletBodyBinder<T> implements AnnotatedRequestArgumentBinder<Body, T> {
    private static final Argument<byte[]> BYTE_ARRAY = Argument.of(byte[].class);

    protected final ConversionService conversionService;
    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;
    private final DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder;
    private final JsonMapper jsonMapper;

    /**
     * Default constructor.
     *
     * @param conversionService           The conversion service
     * @param messageBodyHandlerRegistry  The message body handler registry
     * @param defaultBodyAnnotationBinder The delegate default body binder
     * @param jsonMapper                  The JSON mapper
     */
    protected ServletBodyBinder(ConversionService conversionService,
                                MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                                DefaultBodyAnnotationBinder<T> defaultBodyAnnotationBinder,
                                JsonMapper jsonMapper) {
        this.conversionService = conversionService;
        this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
        this.defaultBodyAnnotationBinder = defaultBodyAnnotationBinder;
        this.jsonMapper = jsonMapper;
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
                if (name != null) {
                    Argument<Map<String, Object>> mapArgument = Argument.mapOf(String.class, Object.class);
                    MessageBodyReader<Map<String, Object>> reader = messageBodyHandlerRegistry.findReader(mapArgument, mediaType).orElse(null);
                    if (reader != null) {
                        try (InputStream inputStream = servletHttpRequest.getInputStream()) {
                            Map<String, Object> map = reader.read(mapArgument, mediaType, source.getHeaders(), inputStream);
                            Object value = map == null ? null : map.get(name);
                            T content = conversionService.convert(value, argument).orElse(null);
                            return () -> Optional.ofNullable(content);
                        } catch (CodecException | IOException e) {
                            throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
                        }
                    }
                }
                MessageBodyReader messageBodyReader = RouteAttributes.getRouteInfo(source)
                    .map(RouteInfo::getMessageBodyReader)
                    .orElse(null);
                if (messageBodyReader != null && !messageBodyReader.isReadable(argument, mediaType)) {
                    messageBodyReader = messageBodyHandlerRegistry.findReader(argument, mediaType).orElse(null);
                }
                if (messageBodyReader != null) {
                    if (CompletionStage.class.isAssignableFrom(type)) {
                        CompletableFuture<?> completableFuture = asFuture(context, source, servletHttpRequest, mediaType, messageBodyReader);
                        return () -> Optional.of((T) completableFuture);
                    }
                    if (Publishers.isConvertibleToPublisher(context.getArgument().getType())) {
                        Object publisher = asPublisher(context, source, servletHttpRequest, mediaType, messageBodyReader, type, name);
                        return () -> (Optional<T>) Optional.ofNullable(publisher);
                    }
                    try (InputStream is = servletHttpRequest.getInputStream()) {
                        Object content = messageBodyReader.read(context.getArgument(), mediaType, source.getHeaders(), is);
                        if (content != null && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                            parsedBody.setParsedBody(content);
                        }
                        return () -> (Optional<T>) Optional.ofNullable(content);
                    } catch (CodecException | IOException e) {
                        throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
                    }
                }

                if (byte[].class.isAssignableFrom(type)) {
                    try (InputStream inputStream = servletHttpRequest.getInputStream()) {
                        byte[] content = inputStream.readAllBytes();
                        return () -> Optional.of((T) content);
                    } catch (IOException e) {
                        throw new CodecException("Unable to read request body: " + e.getMessage(), e);
                    }
                }

                if (type.isArray()) {
                    Argument<List<?>> listArgument = Argument.listOf((Argument) Argument.of(type.getComponentType()));
                    MessageBodyReader<List<?>> reader = messageBodyHandlerRegistry.findReader(listArgument, mediaType).orElse(null);
                    if (reader != null) {
                        try (InputStream inputStream = servletHttpRequest.getInputStream()) {
                            List<?> list = reader.read(listArgument, mediaType, source.getHeaders(), inputStream);
                            if (list == null) {
                                list = List.of();
                            }
                            Object array = Array.newInstance(type.getComponentType(), list.size());
                            for (int i = 0; i < list.size(); i++) {
                                Array.set(array, i, list.get(i));
                            }
                            return () -> Optional.of((T) array);
                        } catch (CodecException | IOException e) {
                            throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
                        }
                    }
                } else {
                    MessageBodyReader<T> reader = messageBodyHandlerRegistry.findReader(argument, mediaType).orElse(null);
                    if (reader != null) {
                        try (InputStream inputStream = servletHttpRequest.getInputStream()) {
                            T content = reader.read(argument, mediaType, source.getHeaders(), inputStream);
                            if (content != null && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                                parsedBody.setParsedBody(content);
                            }
                            return () -> (Optional<T>) Optional.ofNullable(content);
                        } catch (CodecException | IOException e) {
                            throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
        return defaultBodyAnnotationBinder.bind(context, source);
    }

    private @NonNull CompletableFuture<?> asFuture(ArgumentConversionContext<T> context, HttpRequest<?> source, ServletHttpRequest<?, ?> servletHttpRequest, MediaType mediaType, MessageBodyReader messageBodyReader) {
        Argument<Object> typeArgument = (Argument<Object>) context.getArgument().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
        CompletableFuture<?> completableFuture;
        if (servletHttpRequest instanceof ServerHttpRequest<?> serverHttpRequest) {
            if (mediaType.equals(MediaType.APPLICATION_JSON_STREAM_TYPE)) {
                completableFuture = steamJson(serverHttpRequest, typeArgument).single().toFuture();
            } else {
                Class<Object> typeArgumentClass = typeArgument.getType();
                if (CharSequence.class.isAssignableFrom(typeArgumentClass)) {
                    Charset characterEncoding = servletHttpRequest.getCharacterEncoding();
                    completableFuture = serverHttpRequest.byteBody().buffer().thenApply(bb -> bb.toString(characterEncoding));
                } else if (BYTE_ARRAY.getType().isAssignableFrom(typeArgumentClass)) {
                    completableFuture = serverHttpRequest.byteBody().buffer().thenApply(AvailableByteBody::toByteArray);
                } else {
                    completableFuture = serverHttpRequest.byteBody().buffer().thenApply(
                        bb -> messageBodyReader.read(typeArgument, mediaType, source.getHeaders(), bb.toByteBuffer())
                    );
                }
            }
        } else {
            throw new IllegalStateException("Expected ServerHttpRequest");
        }
        if (servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
            completableFuture = completableFuture.thenApply(o -> {
                if (o != null) {
                    parsedBody.setParsedBody(o);
                }
                return o;
            });
        }
        return completableFuture;
    }

    private @NonNull Object asPublisher(ArgumentConversionContext<T> context,
                                        HttpRequest<?> source,
                                        ServletHttpRequest<?, ?> servletHttpRequest,
                                        MediaType mediaType,
                                        MessageBodyReader messageBodyReader,
                                        Class<T> type,
                                        @Nullable String name) {
        Argument<Object> typeArgument = (Argument<Object>) context.getArgument().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
        boolean single = Publishers.isSingle(context.getArgument().getType());
        Publisher<?> publisher;
        if (servletHttpRequest instanceof ServerHttpRequest<?> serverHttpRequest) {
            if (mediaType.equals(MediaType.APPLICATION_JSON_STREAM_TYPE) || !single && mediaType.equals(MediaType.APPLICATION_JSON_TYPE)) {
                Flux<Object> jsonStream = steamJson(serverHttpRequest, typeArgument);
                if (single) {
                    publisher = jsonStream.single();
                } else {
                    publisher = jsonStream;
                }
            } else {
                publisher = Mono.fromCompletionStage(serverHttpRequest.byteBody().buffer())
                    .flatMapMany(bb -> {
                        Class<Object> typeArgumentClass = typeArgument.getType();
                        if (CharSequence.class.isAssignableFrom(typeArgumentClass)) {
                            Charset characterEncoding = servletHttpRequest.getCharacterEncoding();
                            return Mono.just(bb.toString(characterEncoding));
                        }
                        if (BYTE_ARRAY.getType().isAssignableFrom(typeArgumentClass)) {
                            return Mono.just(bb.toByteArray());
                        }
                        if (single) {
                            Object body = null;
                            if (name != null) {
                                Argument<Map<String, Object>> mapArgument = Argument.mapOf(String.class, Object.class);
                                MessageBodyReader<Map<String, Object>> reader = messageBodyHandlerRegistry.findReader(mapArgument, mediaType).orElse(null);
                                if (reader != null) {
                                    Map<String, Object> map = reader.read(mapArgument, mediaType, source.getHeaders(), bb.toByteBuffer());
                                    body = map == null ? null : map.get(name);
                                }
                            } else {
                                body = messageBodyReader.read(typeArgument, mediaType, source.getHeaders(), bb.toByteBuffer());
                            }
                            if (body != null && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                                parsedBody.setParsedBody(body);
                            }
                            return Flux.just(body);
                        } else {
                            Object body = messageBodyReader.read(Argument.listOf(typeArgument), mediaType, source.getHeaders(), bb.toByteBuffer());
                            if (body != null && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                                parsedBody.setParsedBody(body);
                            }
                            return Flux.fromIterable((Iterable<?>) body);
                        }
                    });
            }
        } else {
            if (mediaType.equals(MediaType.APPLICATION_JSON_STREAM_TYPE)) {
                throw new IllegalStateException("Expected ServerHttpRequest");
            }
            try (InputStream is = servletHttpRequest.getInputStream()) {
                if (single) {
                    Object body = messageBodyReader.read(typeArgument, mediaType, source.getHeaders(), is);
                    if (body != null && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                        parsedBody.setParsedBody(body);
                    }
                    publisher = Flux.just(body);
                } else {
                    Object body = messageBodyReader.read(Argument.listOf(typeArgument), mediaType, source.getHeaders(), is);
                    if (body != null && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                        parsedBody.setParsedBody(body);
                    }
                    publisher = Flux.fromIterable((Iterable<?>) body);
                }
            } catch (CodecException | IOException e) {
                throw new CodecException("Unable to decode request body: " + e.getMessage(), e);
            }
        }
        return conversionService.convertRequired(publisher, type);
    }

    private Flux<Object> steamJson(ServerHttpRequest<?> serverRequest, Argument<Object> typeArgument) {
        Processor<byte[], JsonNode> reactiveParser = jsonMapper.createReactiveParser(p -> serverRequest.byteBody().toByteArrayPublisher().subscribe(p), true);
        return Flux.from(reactiveParser)
            .map(node -> {
                try {
                    return jsonMapper.readValueFromTree(node, typeArgument);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
    }

    private boolean isFormSubmission(MediaType contentType) {
        return MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(contentType) || MediaType.MULTIPART_FORM_DATA_TYPE.equals(contentType);
    }

    private record ServletReadable(
        ServletHttpRequest<?, ?> servletHttpRequest) implements Readable {

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
