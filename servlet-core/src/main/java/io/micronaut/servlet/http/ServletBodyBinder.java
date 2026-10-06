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
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.execution.CompletableFutureExecutionFlow;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.io.Readable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.BasicHttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.body.DirectByteBodyAccess;
import io.micronaut.http.HttpRequestWrapper;
import io.micronaut.http.MediaType;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.server.binding.ServerRequestBody;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.DefaultBodyAnnotationBinder;
import io.micronaut.http.bind.binders.PendingRequestBindingResult;
import io.micronaut.http.body.AvailableByteBody;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.exceptions.HttpException;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.RawFormField;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.JsonSyntaxException;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    private static final String UNABLE_TO_DECODE = "Unable to decode request body: ";

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

    /**
     * The body a filter set on the request it continued with, converted to the argument: unsatisfied
     * when it cleared it, which a required body answers with a bad request.
     */
    private BindingResult<T> replacedBody(ArgumentConversionContext<T> context, HttpRequest<?> source, @Nullable String name) {
        Object value = source.getBody().orElse(null);
        if (value != null && name != null) {
            value = value instanceof Map<?, ?> map ? map.get(name) : null;
        }
        if (value == null) {
            return BindingResult.unsatisfied();
        }
        Optional<T> converted = conversionService.convert(value, context);
        return () -> converted;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        final Argument<T> argument = context.getArgument();
        final Class<T> type = argument.getType();
        String name = argument.getAnnotationMetadata().stringValue(Body.class).orElse(null);
        // the server request whose bytes are the body, under the wrappers and mutable views of filters
        ServerHttpRequest<?> server = ServerRequestBody.of(source);
        if (server == null && ServerRequestBody.serverRequest(source) != null) {
            // a filter set the body, even to null: the body is that object, never the bytes of the request
            return replacedBody(context, source, name);
        }
        ServletHttpRequest<?, ?> servletRequest = server == null ? null : servletRequestOf(server);
        if (server != null && servletRequest == null) {
            // e.g. a request a filter continued with that has bytes of its own: the body is read from them
            return bytesOf(context, source, server, name);
        }
        if ((servletRequest != null ? servletRequest : source) instanceof ServletHttpRequest<?, ?> servletHttpRequest) {
            if (Readable.class.isAssignableFrom(type)) {
                Readable readable = new ServletReadable(servletHttpRequest);
                return () -> (Optional<T>) Optional.of(readable);
            }
            if (type == InputStream.class && name == null) {
                // the bytes of the body as they arrive, like on the Netty server
                try {
                    InputStream inputStream = servletHttpRequest.getInputStream();
                    return () -> (Optional<T>) Optional.of(inputStream);
                } catch (IOException e) {
                    throw decodingFailure("Unable to read request body: ", e);
                }
            }
            if (CharSequence.class.isAssignableFrom(type) && name == null) {
                try (BufferedReader bufferedReader = servletHttpRequest.getReader()) {
                    String text = IOUtils.readText(bufferedReader);
                    // a request without content has no body, like on the other runtimes
                    return () -> text.isEmpty() ? Optional.empty() : (Optional<T>) Optional.of(text);
                } catch (IOException e) {
                    HttpException httpException = BodyReadFailures.httpFailure(e);
                    if (httpException != null) {
                        throw httpException;
                    }
                    return new BindingResult<>() {
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
            if (servletHttpRequest instanceof ServerHttpRequest<?> serverRequest
                && serverRequest.byteBody().expectedLength().orElse(-1) == 0) {
                // a request without content has no body, not an empty document to decode: a required body is
                // then missing, like on the other runtimes
                return BindingResult.unsatisfied();
            }
            final MediaType mediaType = source.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
            if (isFormSubmission(mediaType)) {
                if (name != null) {
                    return () -> servletHttpRequest.getParameters().get(name, context);
                } else {
                    if (servletHttpRequest instanceof FormCapableHttpRequest<?> formCapableHttpRequest) {
                        // e.g. a CompletableFuture<Map>: the future of the form converted to its type
                        boolean stage = CompletionStage.class.isAssignableFrom(type);
                        @SuppressWarnings("unchecked")
                        Argument<Object> target = (Argument<Object>) (stage ? argument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT) : argument);
                        CompletableFuture<Optional<Object>> future = Flux.from(formCapableHttpRequest.getRawFormFields())
                            .concatMap(rff -> Mono.fromCompletionStage(rff.byteBody().buffer()).map(buffered -> new RawFormField(rff.metadata(), buffered)))
                            .doOnDiscard(RawFormField.class, RawFormField::close)
                            .collectList()
                            .map(bufferedFields -> {
                                Map<String, List<CloseableByteBody>> bodies = new LinkedHashMap<>();
                                for (RawFormField rff : bufferedFields) {
                                    bodies.computeIfAbsent(rff.metadata().name(), k -> new ArrayList<>(1)).add(rff.byteBody());
                                }
                                Object intermediate = io.micronaut.http.server.multipart.FormRouteCompleter.mapForGetBody(bodies, source.getCharacterEncoding());
                                return stage ? conversionService.convert(intermediate, target) : (Optional<Object>) conversionService.convert(intermediate, context);
                            })
                            .toFuture();
                        if (stage) {
                            CompletableFuture<Object> value = future.thenApply(converted -> converted.orElse(null));
                            return () -> (Optional<T>) Optional.of(value);
                        }
                        BasicHttpAttributes.addRouteWaitsFor(servletHttpRequest, CompletableFutureExecutionFlow.just(future));
                        return new PendingRequestBindingResult<>() {
                            @Override
                            public boolean isPending() {
                                return !future.isDone();
                            }

                            @Override
                            public Optional<T> getValue() {
                                return (Optional<T>) (Optional<?>) future.getNow(Optional.empty());
                            }
                        };
                    }
                    Optional<T> result = conversionService.convert(servletHttpRequest.getParameters().asMap(), context);
                    return () -> result;
                }
            }
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
                        throw decodingFailure(UNABLE_TO_DECODE, e);
                    } catch (RuntimeException e) {
                        throw BodyReadFailures.httpFailureOr(e);
                    }
                }
            }
            MessageBodyReader<?> messageBodyReader = RouteAttributes.getRouteInfo(source)
                .map(RouteInfo::getMessageBodyReader)
                .orElse(null);
            @SuppressWarnings("unchecked")
            Argument<Object> bodyArgumentForReader = (Argument<Object>) (Argument<?>) argument;
            MessageBodyReader<Object> bodyReader = null;
            if (messageBodyReader != null) {
                @SuppressWarnings("unchecked")
                MessageBodyReader<Object> candidate = (MessageBodyReader<Object>) messageBodyReader;
                if (candidate.isReadable(bodyArgumentForReader, mediaType)) {
                    bodyReader = candidate;
                }
            }
            if (bodyReader == null) {
                bodyReader = messageBodyHandlerRegistry.findReader(argument, mediaType)
                    .map(reader -> (MessageBodyReader<Object>) reader)
                    .orElse(null);
            }
            if (bodyReader != null) {
                if (CompletionStage.class.isAssignableFrom(type)) {
                    CompletableFuture<?> completableFuture = asFuture(context, source, servletHttpRequest, mediaType, bodyReader);
                    return () -> Optional.of((T) completableFuture);
                }
                if (Publishers.isConvertibleToPublisher(context.getArgument().getType())) {
                    Object publisher = asPublisher(context, source, servletHttpRequest, mediaType, bodyReader, type, name);
                    return () -> (Optional<T>) Optional.ofNullable(publisher);
                }
                try (InputStream is = servletHttpRequest.getInputStream()) {
                    @SuppressWarnings("unchecked")
                    Argument<Object> bodyArgument = (Argument<Object>) (Argument<?>) context.getArgument();
                    Object content = bodyReader.read(bodyArgument, mediaType, source.getHeaders(), is);
                    if (content != null && source == servletHttpRequest && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                        parsedBody.setParsedBody(content);
                    }
                    return () -> (Optional<T>) Optional.ofNullable(content);
                } catch (CodecException | IOException e) {
                    throw decodingFailure(UNABLE_TO_DECODE, e);
                } catch (RuntimeException e) {
                    throw BodyReadFailures.httpFailureOr(e);
                }
            }

            if (byte[].class.isAssignableFrom(type)) {
                try (InputStream inputStream = servletHttpRequest.getInputStream()) {
                    byte[] content = inputStream.readAllBytes();
                    return () -> Optional.of((T) content);
                } catch (IOException e) {
                    throw decodingFailure("Unable to read request body: ", e);
                }
            }

            if (type.isArray()) {
                Argument<?> componentArgument = Argument.of(type.getComponentType());
                @SuppressWarnings("unchecked")
                Argument<List<?>> listArgument = (Argument<List<?>>) (Argument<?>) Argument.of(List.class, componentArgument);
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
                        throw decodingFailure(UNABLE_TO_DECODE, e);
                    } catch (RuntimeException e) {
                        throw BodyReadFailures.httpFailureOr(e);
                    }
                }
            } else {
                MessageBodyReader<T> reader = messageBodyHandlerRegistry.findReader(argument, mediaType).orElse(null);
                if (reader != null) {
                    try (InputStream inputStream = servletHttpRequest.getInputStream()) {
                        T content = reader.read(argument, mediaType, source.getHeaders(), inputStream);
                        if (content != null && source == servletHttpRequest && servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
                            parsedBody.setParsedBody(content);
                        }
                        return () -> (Optional<T>) Optional.ofNullable(content);
                    } catch (CodecException | IOException e) {
                        throw decodingFailure(UNABLE_TO_DECODE, e);
                    } catch (RuntimeException e) {
                        throw BodyReadFailures.httpFailureOr(e);
                    }
                }
            }
        }
        return defaultBodyAnnotationBinder.bind(context, source);
    }

    /**
     * The servlet request whose bytes are the body of the given server request: the request itself, or the one under
     * views of it that pass its bytes through, e.g. the mutable view of a filter.
     */
    private static @Nullable ServletHttpRequest<?, ?> servletRequestOf(ServerHttpRequest<?> server) {
        HttpRequest<?> current = server;
        while (true) {
            if (current instanceof ServletHttpRequest<?, ?> servletRequest) {
                return servletRequest;
            }
            if (current instanceof ServerHttpRequest<?> && !(current instanceof DirectByteBodyAccess)) {
                // bytes of its own
                return null;
            }
            if (current instanceof HttpRequestWrapper<?> wrapper) {
                current = wrapper.getDelegate();
            } else {
                return null;
            }
        }
    }

    /**
     * Read the body from the bytes of a server request that is not the servlet request, e.g. one a filter continued
     * with that carries another body.
     */
    @SuppressWarnings("unchecked")
    private BindingResult<T> bytesOf(ArgumentConversionContext<T> context, HttpRequest<?> source, ServerHttpRequest<?> server, @Nullable String name) {
        Argument<T> argument = context.getArgument();
        Class<T> type = argument.getType();
        MediaType mediaType = source.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
        CompletableFuture<Optional<T>> future = server.byteBody().buffer().<Optional<T>>thenApply(buffered -> {
            byte[] bytes;
            try (buffered) {
                bytes = buffered.toByteArray();
            }
            if (name == null && CharSequence.class.isAssignableFrom(type)) {
                String text = new String(bytes, source.getCharacterEncoding());
                return text.isEmpty() ? Optional.empty() : conversionService.convert(text, context);
            }
            if (name == null && byte[].class == type) {
                return Optional.of((T) bytes);
            }
            if (bytes.length == 0) {
                return Optional.empty();
            }
            try {
                if (name != null) {
                    Argument<Map<String, Object>> mapArgument = Argument.mapOf(String.class, Object.class);
                    MessageBodyReader<Map<String, Object>> reader = messageBodyHandlerRegistry.findReader(mapArgument, mediaType).orElse(null);
                    if (reader == null) {
                        return Optional.empty();
                    }
                    Map<String, Object> map = reader.read(mapArgument, mediaType, source.getHeaders(), new ByteArrayInputStream(bytes));
                    return conversionService.convert(map == null ? null : map.get(name), context);
                }
                MessageBodyReader<T> reader = messageBodyHandlerRegistry.findReader(argument, mediaType).orElse(null);
                if (reader == null) {
                    return Optional.empty();
                }
                return Optional.ofNullable(reader.read(argument, mediaType, source.getHeaders(), new ByteArrayInputStream(bytes)));
            } catch (CodecException e) {
                throw decodingFailure(UNABLE_TO_DECODE, e);
            }
        }).toCompletableFuture();
        BasicHttpAttributes.addRouteWaitsFor(source, CompletableFutureExecutionFlow.just(future));
        return new PendingRequestBindingResult<>() {
            @Override
            public boolean isPending() {
                return !future.isDone();
            }

            @Override
            public Optional<T> getValue() {
                return future.getNow(Optional.empty());
            }
        };
    }

    private @NonNull CompletableFuture<?> asFuture(ArgumentConversionContext<T> context,
                                                   HttpRequest<?> source,
                                                   ServletHttpRequest<?, ?> servletHttpRequest,
                                                   MediaType mediaType,
                                                   MessageBodyReader<Object> messageBodyReader) {
        Argument<Object> typeArgument = (Argument<Object>) context.getArgument().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
        CompletableFuture<?> completableFuture;
        if (servletHttpRequest instanceof ServerHttpRequest<?> serverHttpRequest) {
            if (mediaType.equals(MediaType.APPLICATION_JSON_STREAM_TYPE)) {
                completableFuture = streamJson(serverHttpRequest, typeArgument).single().toFuture();
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
                                        MessageBodyReader<Object> messageBodyReader,
                                        Class<T> type,
                                        @Nullable String name) {
        Argument<Object> typeArgument = (Argument<Object>) context.getArgument().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
        boolean single = Publishers.isSingle(context.getArgument().getType());
        Publisher<?> publisher;
        if (servletHttpRequest instanceof ServerHttpRequest<?> serverHttpRequest) {
            if (mediaType.equals(MediaType.APPLICATION_JSON_STREAM_TYPE)) {
                Flux<Object> jsonStream = streamJson(serverHttpRequest, typeArgument);
                publisher = single ? jsonStream.single() : jsonStream;
            } else {
                publisher = Mono.fromCompletionStage(serverHttpRequest.byteBody().buffer())
                    .flatMapMany(bb -> publishBuffered(bb, source, servletHttpRequest, mediaType, messageBodyReader, typeArgument, single, name));
            }
        } else {
            publisher = publishFromStream(source, servletHttpRequest, mediaType, messageBodyReader, typeArgument, single);
        }
        return conversionService.convertRequired(publisher, type);
    }

    /**
     * Reads the body from the request stream for a request that is not a {@link ServerHttpRequest}.
     */
    private static Publisher<?> publishFromStream(HttpRequest<?> source,
                                                  ServletHttpRequest<?, ?> servletHttpRequest,
                                                  MediaType mediaType,
                                                  MessageBodyReader<Object> messageBodyReader,
                                                  Argument<Object> typeArgument,
                                                  boolean single) {
        if (mediaType.equals(MediaType.APPLICATION_JSON_STREAM_TYPE)) {
            throw new IllegalStateException("Expected ServerHttpRequest");
        }
        try (InputStream is = servletHttpRequest.getInputStream()) {
            Argument<Object> readAs = single ? typeArgument : listOf(typeArgument);
            Object body = messageBodyReader.read(readAs, mediaType, source.getHeaders(), is);
            return publishParsed(body, single, servletHttpRequest);
        } catch (CodecException | IOException e) {
            throw decodingFailure(UNABLE_TO_DECODE, e);
        } catch (RuntimeException e) {
            throw BodyReadFailures.httpFailureOr(e);
        }
    }

    /**
     * Turns a buffered body into the publisher a reactive body argument expects.
     */
    @SuppressWarnings("java:S107") // every value the read needs, passed once from the caller
    /**
     * Whether a JSON body is one object rather than an array of elements.
     */
    private static boolean startsWithObject(byte[] bytes) {
        for (byte b : bytes) {
            if (!Character.isWhitespace(b)) {
                return b == '{';
            }
        }
        return false;
    }

    private Publisher<?> publishBuffered(AvailableByteBody bb,
                                         HttpRequest<?> source,
                                         ServletHttpRequest<?, ?> servletHttpRequest,
                                         MediaType mediaType,
                                         MessageBodyReader<Object> messageBodyReader,
                                         Argument<Object> typeArgument,
                                         boolean single,
                                         @Nullable String name) {
        Class<Object> typeArgumentClass = typeArgument.getType();
        if (CharSequence.class.isAssignableFrom(typeArgumentClass)) {
            return Mono.just(bb.toString(servletHttpRequest.getCharacterEncoding()));
        }
        if (BYTE_ARRAY.getType().isAssignableFrom(typeArgumentClass)) {
            return Mono.just(bb.toByteArray());
        }
        // the bytes can be taken once
        byte[] bytes = bb.toByteArray();
        Object body;
        if (!single && !startsWithObject(bytes)) {
            Argument<Object> listArgument = listOf(typeArgument);
            body = messageBodyReader.read(listArgument, mediaType, source.getHeaders(), new ByteArrayInputStream(bytes));
        } else if (!single && name == null) {
            // a single document, not an array of them: the publisher emits that one element, like on Netty
            body = List.of(messageBodyReader.read(typeArgument, mediaType, source.getHeaders(), new ByteArrayInputStream(bytes)));
        } else if (name != null) {
            Argument<Map<String, Object>> mapArgument = Argument.mapOf(String.class, Object.class);
            MessageBodyReader<Map<String, Object>> reader = messageBodyHandlerRegistry.findReader(mapArgument, mediaType).orElse(null);
            Map<String, Object> map = reader == null ? null : reader.read(mapArgument, mediaType, source.getHeaders(), new ByteArrayInputStream(bytes));
            body = map == null ? null : map.get(name);
        } else {
            body = messageBodyReader.read(typeArgument, mediaType, source.getHeaders(), new ByteArrayInputStream(bytes));
        }
        return publishParsed(body, single, servletHttpRequest);
    }

    /**
     * Records a parsed body on the request and publishes it: as one item, or item by item for a list.
     */
    private static Publisher<?> publishParsed(@Nullable Object body, boolean single, ServletHttpRequest<?, ?> servletHttpRequest) {
        if (body == null) {
            return Flux.empty();
        }
        if (servletHttpRequest instanceof ParsedBodyHolder parsedBody) {
            parsedBody.setParsedBody(body);
        }
        return single ? Flux.just(body) : Flux.fromIterable((Iterable<?>) body);
    }

    @SuppressWarnings("unchecked")
    private static Argument<Object> listOf(Argument<Object> typeArgument) {
        return (Argument<Object>) (Argument<?>) Argument.listOf(typeArgument);
    }

    private Flux<Object> streamJson(ServerHttpRequest<?> serverRequest, Argument<Object> typeArgument) {
        Processor<byte[], JsonNode> reactiveParser = jsonMapper.createReactiveParser(p -> serverRequest.byteBody().toByteArrayPublisher().subscribe(p), true);
        return Flux.from(reactiveParser)
            .map(node -> {
                try {
                    return jsonMapper.readValueFromTree(node, typeArgument);
                } catch (IOException e) {
                    throw decodingFailure("Unable to decode JSON stream: ", e);
                }
            });
    }

    private boolean isFormSubmission(MediaType contentType) {
        return MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(contentType) || MediaType.MULTIPART_FORM_DATA_TYPE.equals(contentType);
    }

    /**
     * Turns a failure to read or decode the body into the exception to throw: an HTTP failure that the body
     * itself raised, such as {@link io.micronaut.http.exceptions.ContentLengthExceededException} when
     * {@code micronaut.server.max-request-size} is exceeded, is rethrown as is so that it maps to its own status,
     * and anything else becomes a {@link CodecException}.
     *
     * @param message The message prefix for a codec failure
     * @param e The failure
     * @return The exception to throw
     */
    private static RuntimeException decodingFailure(String message, Exception e) {
        HttpException httpException = BodyReadFailures.httpFailure(e);
        if (httpException != null) {
            return httpException;
        }
        if (e instanceof CodecException codecException) {
            Throwable cause = codecException.getCause();
            if (cause instanceof JsonSyntaxException || cause instanceof IOException && cause.getClass().getName().startsWith("com.fasterxml.jackson.core.")) {
                // a document that does not parse is a JSON syntax error, which the error routes of the application
                // and the JSON exception handler answer, like on the other runtimes
                return new ConversionErrorException(Argument.OBJECT_ARGUMENT,
                    cause instanceof JsonSyntaxException syntax ? syntax : new JsonSyntaxException(cause));
            }
            // as the reader failed
            return codecException;
        }
        return new CodecException(message + e.getMessage(), e);
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
