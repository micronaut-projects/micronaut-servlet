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
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.execution.CompletableFutureExecutionFlow;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.io.Readable;
import io.micronaut.core.io.buffer.ReadBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.http.BasicHttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.PendingRequestBindingResult;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.CompletedAttribute;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.multipart.PartData;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.reactive.execution.ReactiveExecutionFlow;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.http.server.multipart.FormRouteCompleter;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.http.codec.CodecException;
import io.micronaut.servlet.http.ServletExchange;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A binder capable of binding servlet multipart requests.
 *
 * @param <T> The argument type
 * @author graemerocher
 * @since 1.0.0
 */
public class ServletPartBinder<T> implements AnnotatedRequestArgumentBinder<Part, T> {

    private final ConversionService conversionService;
    private final BeanProvider<FormFactory> formFactoryProvider;
    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;

    /**
     * Default constructor.
     *
     * @param conversionService   The conversion service.
     * @param formFactoryProvider The form factory provider.
     * @param messageBodyHandlerRegistry The message body handler registry
     */
    ServletPartBinder(ConversionService conversionService,
                      BeanProvider<FormFactory> formFactoryProvider,
                      MessageBodyHandlerRegistry messageBodyHandlerRegistry) {
        this.conversionService = conversionService;
        this.formFactoryProvider = formFactoryProvider;
        this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
    }

    @Override
    public Class<Part> getAnnotationType() {
        return Part.class;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        if (source instanceof ServletExchange<?, ?> exchange) {
            final HttpServletRequest nativeRequest = (HttpServletRequest) exchange.getRequest().getNativeRequest();
            final Argument<T> argument = context.getArgument();
            final String partName = context.getAnnotationMetadata().stringValue(Part.class).orElse(argument.getName());
            final MediaType requestContentType = source.getContentType().orElse(null);
            final boolean isMultipart = requestContentType != null && requestContentType.matches(MediaType.MULTIPART_FORM_DATA_TYPE);

            if (!isMultipart && source instanceof FormCapableHttpRequest<?> formRequest && formRequest.hasFormBody()) {
                BindingResult<T> result = bindFromForm(formRequest, context, partName);
                if (result != BindingResult.UNSATISFIED) {
                    return result;
                }
            }

            if (isMultipart) {
                return bindFromMultipart(context, nativeRequest, partName);
            }
        }
        return BindingResult.UNSATISFIED;
    }

    private BindingResult<T> bindFromMultipart(ArgumentConversionContext<T> context,
                                               HttpServletRequest nativeRequest,
                                               String partName) {
        final Argument<T> argument = context.getArgument();
        final jakarta.servlet.http.Part part;
        try {
            part = nativeRequest.getPart(partName);
        } catch (IOException | ServletException e) {
            throw new InternalServerException("Error reading part [" + partName + "]: " + e.getMessage(), e);
        }
        if (part == null) {
            return BindingResult.UNSATISFIED;
        }
        final Class<T> type = argument.getType();
        if (jakarta.servlet.http.Part.class.isAssignableFrom(type)) {
            //noinspection unchecked
            return () -> (Optional<T>) Optional.of(part);
        }

        Optional<T> messageBodyValue = readUsingMessageBodyReader(context, part, partName);
        if (messageBodyValue.isPresent()) {
            return () -> messageBodyValue;
        }

        if (Readable.class.isAssignableFrom(type)) {
            //noinspection unchecked
            return () -> (Optional<T>) Optional.of(new Readable() {
                @NonNull
                @Override
                public String getName() {
                    return part.getName();
                }

                @Override
                public Reader asReader() throws IOException {
                    final Charset charset = Optional.ofNullable(part.getContentType()).map(MediaType::new)
                        .flatMap(MediaType::getCharset).orElse(StandardCharsets.UTF_8);
                    return new InputStreamReader(asInputStream(), charset);
                }

                @NonNull
                @Override
                public InputStream asInputStream() throws IOException {
                    return part.getInputStream();
                }

                @Override
                public boolean exists() {
                    return true;
                }
            });
        } else if (String.class.isAssignableFrom(type)) {
            try (BufferedReader reader = newReader(part)) {
                final String content = IOUtils.readText(reader);
                return () -> (Optional<T>) Optional.of(content);
            } catch (IOException e) {
                throw new HttpStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to read part [" + partName + "]: " + e.getMessage()
                );
            }
        } else if (byte[].class.isAssignableFrom(type)) {
            try (InputStream is = part.getInputStream()) {
                final byte[] content = is.readAllBytes();
                return () -> (Optional<T>) Optional.of(content);
            } catch (IOException e) {
                throw new HttpStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to read part [" + partName + "]: " + e.getMessage()
                );
            }
        } else if (CompletedFileUpload.class.isAssignableFrom(type)) {
            try {
                CompletedFileUpload completedFileUpload = ServletCompletedFileUploadFactory.create(part);
                //noinspection unchecked
                return () -> (Optional<T>) Optional.of(completedFileUpload);
            } catch (IOException e) {
                throw new HttpStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to read part [" + partName + "]: " + e.getMessage()
                );
            }
        } else {
            Optional<T> converted = conversionService.convert(part, context);
            if (converted.isPresent()) {
                return () -> converted;
            }
        }
        return BindingResult.UNSATISFIED;
    }

    private BindingResult<T> bindFromForm(FormCapableHttpRequest<?> formRequest,
                                          ArgumentConversionContext<T> context,
                                          String partName) {
        FormFactory factory = formFactoryProvider.get();
        if (factory == null) {
            return BindingResult.UNSATISFIED;
        }
        Argument<T> argument = context.getArgument();
        Class<T> argumentType = argument.getType();

        if (Publisher.class.isAssignableFrom(argumentType)) {
            return bindPublisher(factory, formRequest, context, partName);
        }

        return bindSingleValue(factory, formRequest, context, partName, false);
    }

    private BindingResult<T> bindPublisher(FormFactory factory,
                                           FormCapableHttpRequest<?> formRequest,
                                           ArgumentConversionContext<T> context,
                                           String partName) {
        Argument<T> argument = context.getArgument();
        Argument<?> elementArgument = argument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
        Class<?> elementType = elementArgument.getType();
        FormRouteCompleter completer = factory.getOrCreateCompleter(formRequest);

        Flux<?> flux;
        if (PartData.class.isAssignableFrom(elementType)) {
            flux = Flux.from(completer.subscribeField(partName,
                    new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC, argument)))
                .concatMap(raw -> Flux.from(raw.byteBody().toReadBufferPublisher())
                    .map(readBuffer -> new PartData(raw.metadata(), readBuffer.move()))
                    .doFinally(signalType -> closeRaw(signalType, raw)))
                .doOnDiscard(PartData.class, PartData::close);
        } else if (StreamingFileUpload.class.isAssignableFrom(elementType)) {
            flux = Flux.from(completer.subscribeField(partName,
                    new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC, argument)))
                .map(factory::streamFileUpload)
                .doOnDiscard(StreamingFileUpload.class, StreamingFileUpload::close);
        } else if (Publisher.class.isAssignableFrom(elementType)) {
            Argument<?> nestedArgument = elementArgument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
            flux = Flux.from(completer.subscribeField(partName,
                    new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC, argument)))
                .map(raw -> Flux.from(raw.byteBody().toReadBufferPublisher())
                    .map(readBuffer -> {
                        if (nestedArgument.isAssignableFrom(ReadBuffer.class)) {
                            return readBuffer;
                        }
                        try (ReadBuffer closable = readBuffer) {
                            return conversionService.convertRequired(closable, nestedArgument);
                        }
                    })
                    .doFinally(signalType -> closeRaw(signalType, raw)));
        } else if (CompletedFileUpload.class.isAssignableFrom(elementType)) {
            flux = Flux.from(Publishers.bufferNow(Flux.from(completer.subscribeField(partName,
                    new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC_NO_BACKPRESSURE, argument)))
                .flatMap(raw -> ReactiveExecutionFlow.toPublisher(factory.completeFileUpload(formRequest, raw)))));
        } else if (CompletedAttribute.class.isAssignableFrom(elementType)) {
            flux = Flux.from(Publishers.bufferNow(Flux.from(completer.subscribeField(partName,
                        new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC_NO_BACKPRESSURE, argument)))
                    .flatMap(raw -> ReactiveExecutionFlow.toPublisher(factory.completeAttribute(formRequest, raw)))))
                .doOnDiscard(CompletedAttribute.class, attr -> attr.closeAsync(factory.getDiskWriteExecutor()));
        } else if (CompletedPart.class.isAssignableFrom(elementType)) {
            flux = Flux.from(Publishers.bufferNow(Flux.from(completer.subscribeField(partName,
                        new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC_NO_BACKPRESSURE, argument)))
                    .flatMap(raw -> ReactiveExecutionFlow.toPublisher(factory.completePart(formRequest, raw)))))
                .doOnDiscard(CompletedPart.class, part -> part.closeAsync(factory.getDiskWriteExecutor()));
        } else {
            @SuppressWarnings("unchecked")
            ArgumentConversionContext<Object> conversionContext = (ArgumentConversionContext<Object>) ConversionContext.of(elementArgument);
            flux = Flux.from(Publishers.bufferNow(Flux.from(completer.subscribeField(partName,
                        new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC_NO_BACKPRESSURE, argument)))
                    .flatMap(raw -> ReactiveExecutionFlow.toPublisher(factory.completePart(formRequest, raw)))))
                .publishOn(Schedulers.fromExecutor(factory.getDiskWriteExecutor()))
                .flatMap(part -> Mono.justOrEmpty(convertCompletedPart(factory, conversionContext, part)));
        }

        Optional<T> converted = conversionService.convert(flux, context);
        T result = converted.orElseGet(() -> {
            @SuppressWarnings("unchecked")
            T castFlux = (T) flux;
            return castFlux;
        });
        return () -> Optional.of(result);
    }

    private BindingResult<T> bindSingleValue(FormFactory factory,
                                             FormCapableHttpRequest<?> formRequest,
                                             ArgumentConversionContext<T> context,
                                             String inputName,
                                             boolean skipClaimed) {
        FormRouteCompleter completer = factory.getOrCreateCompleter(formRequest);
        if (skipClaimed && completer.isClaimed(inputName)) {
            return BindingResult.unsatisfied();
        }
        CompletableFuture<Optional<T>> completableFuture = Mono.from(completer.subscribeField(inputName, new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.WAITS_FOR_FULL, context.getArgument())))
            .flatMap(rff -> Mono.from(ReactiveExecutionFlow.toPublisher(factory.completePart(formRequest, rff))))
            .map(d -> {
                boolean skipClose = false;
                try {
                    Optional<T> converted = conversionService.convert(d, context);
                    if (converted.isPresent() && converted.get() == d) {
                        skipClose = true;
                    }
                    return converted;
                } finally {
                    if (!skipClose) {
                        d.closeAsync(factory.getDiskWriteExecutor());
                    }
                }
            })
            .toFuture();
        BasicHttpAttributes.addRouteWaitsFor(formRequest, CompletableFutureExecutionFlow.just(completableFuture));

        return new PendingRequestBindingResult<>() {

            @Override
            public boolean isPending() {
                return !completableFuture.isDone();
            }

            @Override
            public Optional<T> getValue() {
                return completableFuture.getNow(null);
            }
        };
    }

    private <X> Optional<X> convertCompletedPart(FormFactory factory,
                                                 ArgumentConversionContext<X> context,
                                                 CompletedPart completedPart) {
        boolean reuse = false;
        Optional<X> converted = conversionService.convert(completedPart, context);
        if (converted.isPresent() && converted.get() == completedPart) {
            reuse = true;
        }

        if (converted.isEmpty()) {
            Class<X> targetType = context.getArgument().getType();
            try {
                if (CharSequence.class.isAssignableFrom(targetType)) {
                    Charset charset = resolveCharset(completedPart);
                    String value = new String(completedPart.getBytes(), charset);
                    //noinspection unchecked
                    converted = Optional.of((X) value);
                } else if (byte[].class.isAssignableFrom(targetType)) {
                    //noinspection unchecked
                    converted = Optional.of((X) completedPart.getBytes());
                } else if (InputStream.class.isAssignableFrom(targetType)) {
                    //noinspection unchecked
                    converted = Optional.of((X) completedPart.getInputStream());
                    reuse = true;
                } else if (Readable.class.isAssignableFrom(targetType)) {
                    Charset charset = resolveCharset(completedPart);
                    String value = new String(completedPart.getBytes(), charset);
                    Readable readable = new SimpleReadable(completedPart.getName(), value, charset);
                    //noinspection unchecked
                    converted = Optional.of((X) readable);
                } else if (CompletedPart.class.isAssignableFrom(targetType)) {
                    //noinspection unchecked
                    converted = Optional.of((X) completedPart);
                    reuse = true;
                }
            } catch (IOException e) {
                throw new InternalServerException("Error reading part [" + completedPart.getName() + "]: " + e.getMessage(), e);
            }
        }

        if (converted.isEmpty()) {
            completedPart.closeAsync(factory.getDiskWriteExecutor());
            return Optional.empty();
        }

        if (!reuse) {
            completedPart.closeAsync(factory.getDiskWriteExecutor());
        }
        return converted;
    }

    private Optional<T> readUsingMessageBodyReader(ArgumentConversionContext<T> context,
                                                   jakarta.servlet.http.Part part,
                                                   String partName) {
        MediaType mediaType = Optional.ofNullable(part.getContentType()).map(MediaType::new).orElse(null);
        Argument<T> argument = context.getArgument();
        MessageBodyReader<T> reader = messageBodyHandlerRegistry.findReader(argument, mediaType).orElse(null);
        if (reader == null) {
            return Optional.empty();
        }

        SimpleHttpHeaders headers = new SimpleHttpHeaders(conversionService);
        for (String headerName : part.getHeaderNames()) {
            for (String headerValue : part.getHeaders(headerName)) {
                headers.add(headerName, headerValue);
            }
        }

        try (InputStream inputStream = part.getInputStream()) {
            T value = reader.read(argument, mediaType, headers, inputStream);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (IOException e) {
            throw new HttpStatusException(
                HttpStatus.BAD_REQUEST,
                "Unable to read part [" + partName + "]: " + e.getMessage()
            );
        } catch (CodecException e) {
            throw new HttpStatusException(
                HttpStatus.BAD_REQUEST,
                "Unable to decode part [" + partName + "]: " + e.getMessage()
            );
        }
    }

    private Charset resolveCharset(CompletedPart completedPart) {
        return Optional.ofNullable(completedPart.getMetadata().mediaType())
            .flatMap(MediaType::getCharset)
            .orElse(StandardCharsets.UTF_8);
    }

    private void closeRaw(SignalType signalType, io.micronaut.http.multipart.RawFormField raw) {
        if (signalType == SignalType.CANCEL || signalType == SignalType.ON_COMPLETE) {
            raw.close();
        }
    }

    private BufferedReader newReader(jakarta.servlet.http.Part part) throws IOException {
        final Charset charset = Optional.ofNullable(part.getContentType())
            .map(MediaType::new)
            .flatMap(MediaType::getCharset)
            .orElse(StandardCharsets.UTF_8);
        final InputStreamReader inputStreamReader = new InputStreamReader(part.getInputStream(), charset);
        return new BufferedReader(inputStreamReader);
    }

    private record SimpleReadable(String name, String value, Charset charset) implements Readable {
        private SimpleReadable(String name, String value, Charset charset) {
            this.name = Objects.requireNonNull(name, "name");
            this.value = Objects.requireNonNull(value, "value");
            this.charset = Objects.requireNonNull(charset, "charset");
        }

        @NonNull
        @Override
        public String getName() {
            return name;
        }

        @Override
        public Reader asReader() {
            return new StringReader(value);
        }

        @NonNull
        @Override
        public InputStream asInputStream() {
            return new ByteArrayInputStream(value.getBytes(charset));
        }

        @Override
        public boolean exists() {
            return true;
        }
    }
}
