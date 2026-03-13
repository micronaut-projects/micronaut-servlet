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
import org.jspecify.annotations.NonNull;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionError;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.execution.CompletableFutureExecutionFlow;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.io.Readable;
import io.micronaut.core.io.buffer.ReadBufferFactory;
import io.micronaut.core.type.Argument;
import io.micronaut.http.BasicHttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.PendingRequestBindingResult;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.multipart.FormFieldMetadata;
import io.micronaut.http.multipart.PartData;
import io.micronaut.http.reactive.execution.ReactiveExecutionFlow;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.http.server.multipart.FormRouteCompleter;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.servlet.http.ServletExchange;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A binder capable of binding servlet multipart requests.
 * @param <T> The argument type
 *
 * @author graemerocher
 * @since 1.0.0
 */
public class ServletPartBinder<T> implements AnnotatedRequestArgumentBinder<Part, T> {

    private final MediaTypeCodecRegistry codecRegistry;
    private final ConversionService conversionService;
    private final BeanProvider<FormFactory> formFactoryProvider;

    /**
     * Default constructor.
     * @param codecRegistry The codec registry.
     */
    ServletPartBinder(MediaTypeCodecRegistry codecRegistry,
                      ConversionService conversionService,
                      BeanProvider<FormFactory> formFactoryProvider) {
        this.codecRegistry = codecRegistry;
        this.conversionService = conversionService;
        this.formFactoryProvider = formFactoryProvider;
    }

    @Override
    public Class<Part> getAnnotationType() {
        return Part.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        if (source instanceof FormCapableHttpRequest<?> formRequest && formRequest.hasFormBody()) {
            final Argument<T> argument = context.getArgument();
            final String partName = context.getAnnotationMetadata().stringValue(Part.class).orElse(argument.getName());
            if (Publishers.isConvertibleToPublisher(argument.getType())) {
                BindingResult<?> publisherBinding = bindPublisherPart((ArgumentConversionContext<Publisher<?>>) context, formRequest, partName);
                return (BindingResult<T>) publisherBinding;
            }
            return bindFormPart(context, formRequest, partName);
        }
        if (source instanceof ServletExchange<?, ?> exchange) {
            final HttpServletRequest nativeRequest = (HttpServletRequest) exchange.getRequest().getNativeRequest();
            final Argument<T> argument = context.getArgument();
            final String partName = context.getAnnotationMetadata().stringValue(Part.class).orElse(argument.getName());
            final jakarta.servlet.http.Part part;
            try {
                part = nativeRequest.getPart(partName);
            } catch (UnsupportedOperationException e) {
                return BindingResult.UNSATISFIED;
            } catch (IOException | ServletException e) {
                throw new InternalServerException("Error reading part [" + partName + "]: " + e.getMessage(), e);
            }
            if (part != null) {
                final Class<T> type = argument.getType();
                if (jakarta.servlet.http.Part.class.isAssignableFrom(type)) {
                    //noinspection unchecked
                    return () -> (Optional<T>) Optional.of(part);
                } else if (Readable.class.isAssignableFrom(type)) {
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
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

                        int nRead;
                        byte[] data = new byte[16384];

                        while ((nRead = is.read(data, 0, data.length)) != -1) {
                            buffer.write(data, 0, nRead);
                        }
                        final byte[] content = buffer.toByteArray();
                        return () -> (Optional<T>) Optional.of(content);
                    } catch (IOException e) {
                        throw new HttpStatusException(
                                HttpStatus.BAD_REQUEST,
                                "Unable to read part [" + partName + "]: " + e.getMessage()
                        );
                    }

                } else if (CompletedFileUpload.class.isAssignableFrom(type)) {
                    try {
                        CompletedFileUpload completedFileUpload = completedFileUpload(part);
                        //noinspection unchecked
                        return () -> (Optional<T>) Optional.of(completedFileUpload);
                    } catch (IOException e) {
                        throw new HttpStatusException(
                                HttpStatus.BAD_REQUEST,
                                "Unable to read part [" + partName + "]: " + e.getMessage()
                        );
                    }
                } else {
                    final MediaType contentType =
                            Optional.ofNullable(part.getContentType()).map(MediaType::new)
                            .orElse(null);
                    if (contentType != null) {
                        final MediaTypeCodec codec = codecRegistry.findCodec(contentType, type).orElse(null);
                        if (codec != null) {
                            try (InputStream inputStream = part.getInputStream()) {
                                final T content = codec.decode(argument, inputStream);
                                return () -> (Optional<T>) Optional.of(content);
                            } catch (IOException e) {
                                throw new HttpStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Unable to read part [" + partName + "]: " + e.getMessage()
                                );
                            }
                        }
                    }
                }
            }
        }
        return BindingResult.UNSATISFIED;
    }

    private BindingResult<T> bindFormPart(ArgumentConversionContext<T> context,
                                          FormCapableHttpRequest<?> formRequest,
                                          String partName) {
        FormFactory formFactory = formFactoryProvider.get();
        FormRouteCompleter completer = formFactory.getOrCreateCompleter(formRequest);

        CompletableFuture<Optional<T>> completableFuture = Mono.from(completer.subscribeField(partName,
                new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.WAITS_FOR_FULL, context.getArgument())))
            .flatMap(rawFormField -> Mono.from(ReactiveExecutionFlow.toPublisher(formFactory.completePart(formRequest, rawFormField))))
            .map(completedPart -> {
                boolean skipClose = false;
                try {
                    Optional<T> converted = conversionService.convert(completedPart, context);
                    if (converted.isPresent() && converted.get() == completedPart) {
                        skipClose = true;
                    }
                    return converted;
                } finally {
                    if (!skipClose) {
                        completedPart.closeAsync(formFactory.getDiskWriteExecutor());
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
            public List<ConversionError> getConversionErrors() {
                return context.getLastError().map(List::of).orElseGet(List::of);
            }

            @Override
            public Optional<T> getValue() {
                Optional<T> res = completableFuture.getNow(Optional.empty());
                if (res == null) {
                    res = Optional.empty();
                }
                return res;
            }
        };
    }

    private BindingResult<Publisher<?>> bindPublisherPart(ArgumentConversionContext<Publisher<?>> context,
                                                           FormCapableHttpRequest<?> formRequest,
                                                           String partName) {
        Argument<Publisher<?>> argument = context.getArgument();
        Argument<?> contentArgument = argument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
        Class<?> contentTypeClass = contentArgument.getType();
        FormFactory formFactory = formFactoryProvider.get();

        Flux<?> publisher;
        if (contentTypeClass == PartData.class) {
            publisher = Flux.from(formFactory.getOrCreateCompleter(formRequest)
                    .subscribeField(partName, new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC, argument)))
                .concatMap(raw -> Flux.from(raw.byteBody().toReadBufferPublisher())
                    .map(readBuffer -> new PartData(raw.metadata(), readBuffer.move())))
                .doOnDiscard(PartData.class, PartData::close);
        } else {
            Flux<CompletedPart> completedParts = Flux.from(Publishers.bufferNow(
                Flux.from(formFactory.getOrCreateCompleter(formRequest)
                        .subscribeField(partName,
                            new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC_NO_BACKPRESSURE, argument)))
                    .flatMap(raw -> ReactiveExecutionFlow.toPublisher(formFactory.completePart(formRequest, raw)))));
            if (contentTypeClass == CompletedPart.class) {
                publisher = completedParts;
            } else {
                publisher = completedParts
                    .publishOn(Schedulers.fromExecutor(formFactory.getDiskWriteExecutor()))
                    .map(completedPart -> {
                        try (completedPart) {
                            return conversionService.convertRequired(completedPart, contentArgument);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            }
        }

        return () -> Optional.of(publisher);
    }

    private BufferedReader newReader(jakarta.servlet.http.Part part) throws IOException {
        final Charset charset = Optional.ofNullable(part.getContentType())
                .map(MediaType::new)
                .flatMap(MediaType::getCharset)
                .orElse(StandardCharsets.UTF_8);
        final InputStreamReader inputStreamReader = new InputStreamReader(part.getInputStream(), charset);
        return new BufferedReader(inputStreamReader);
    }

    private static CompletedFileUpload completedFileUpload(jakarta.servlet.http.Part part) throws IOException {
        MediaType mediaType = Optional.ofNullable(part.getContentType())
                .map(MediaType::new)
                .orElse(null);
        FormFieldMetadata metadata = new FormFieldMetadata(part.getName(), part.getSubmittedFileName(), mediaType);
        try (InputStream inputStream = part.getInputStream()) {
            return CompletedFileUpload.ofMemory(metadata, ReadBufferFactory.getJdkFactory().copyOf(inputStream));
        }
    }

}
