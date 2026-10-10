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

import io.micronaut.context.BeanProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.bind.ArgumentBinder.BindingResult;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.execution.CompletableFutureExecutionFlow;
import io.micronaut.core.io.Readable;
import io.micronaut.core.io.buffer.ReadBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.http.BasicHttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.LifecycleHttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.bind.binders.PendingRequestBindingResult;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.CompletedAttribute;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.multipart.PartData;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.micronaut.http.reactive.execution.ReactiveExecutionFlow;
import io.micronaut.http.server.binding.FormBinding;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.http.server.multipart.FormRouteCompleter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Binds a {@link io.micronaut.http.annotation.Part} argument from the fields of a form, read through
 * {@link FormCapableHttpRequest}: what does not depend on how the runtime parses a multipart request itself, shared
 * by the servlet engine and the runtimes that have no parser of their own.
 *
 * @param <T> The argument type
 * @since 6.3.0
 */
@Internal
public final class FormPartBinder<T> {

    private final ConversionService conversionService;
    private final BeanProvider<FormFactory> formFactoryProvider;

    /**
     * @param conversionService   The conversion service
     * @param formFactoryProvider The form factory provider
     */
    public FormPartBinder(ConversionService conversionService, BeanProvider<FormFactory> formFactoryProvider) {
        this.conversionService = conversionService;
        this.formFactoryProvider = formFactoryProvider;
    }

    /**
     * Bind the argument from the form a filter set, or from the form of the request when it is read through the
     * {@link FormCapableHttpRequest}.
     *
     * @param context        The conversion context of the argument
     * @param source         The request, or e.g. the mutable view of the request a filter continued with
     * @param boundName      The name of the field
     * @param fieldFromBody  Whether a single field is read from the form decoded from the byte body, e.g. because
     *                       the container cannot parse the parts itself
     * @return The binding result, or {@code null} if the runtime binds the argument itself
     */
    public @Nullable BindingResult<T> bindForm(ArgumentConversionContext<T> context, HttpRequest<?> source, String boundName, BooleanSupplier fieldFromBody) {
        // the request itself, or e.g. the mutable view of the request a filter continued with
        BindingResult<T> replaced = FormBinding.bindReplaced(context, source, conversionService, boundName);
        if (replaced != null) {
            // a filter set the body: the form it set, never the bytes of the request
            return replaced;
        }
        FormCapableHttpRequest<?> form = FormBinding.formRequest(source);
        FormFactory formFactory = form != null && form.hasFormBody() ? formFactoryProvider.get() : null;
        if (form == null || formFactory == null) {
            return null;
        }
        if (FormBinding.isBound(context.getArgument())) {
            // FileUpload, List<FileUpload>, FormPart and their Optional
            return FormBinding.bind(context, source, form, formFactory, conversionService);
        }
        BindingResult<T> fromForm = FormBinding.bindField(conversionService, context, source, form, formFactory, boundName);
        if (fromForm != null) {
            // the form is read whole by a FormData or FormParts argument of the route
            return fromForm;
        }
        if (StreamingFileUpload.class == context.getArgument().getType()) {
            return bindStreamingFileUpload(context, form, formFactory, boundName);
        }
        if (Publisher.class.isAssignableFrom(context.getArgument().getType())) {
            // the parts as they arrive
            return bindPublisher(formFactory, form, context, boundName);
        }
        if (fieldFromBody.getAsBoolean()) {
            return bindSingleValue(formFactory, form, context, boundName);
        }
        return null;
    }

    /**
     * A file streamed from the part of its name, which the route gets once the part starts.
     */
    @SuppressWarnings("unchecked")
    private BindingResult<T> bindStreamingFileUpload(ArgumentConversionContext<T> context,
                                                     FormCapableHttpRequest<?> form,
                                                     FormFactory formFactory,
                                                     String partName) {
        CompletableFuture<? extends StreamingFileUpload> completableFuture =
            Mono.from(formFactory.getOrCreateCompleter(form).subscribeField(partName,
                    new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.WAITS_FOR_START, context.getArgument())))
                .map(formFactory::streamFileUpload)
                .toFuture();
        BasicHttpAttributes.addRouteWaitsFor(form, CompletableFutureExecutionFlow.just(completableFuture));
        return new PendingRequestBindingResult<>() {
            @Override
            public boolean isPending() {
                return !completableFuture.isDone();
            }

            @Override
            public Optional<T> getValue() {
                return Optional.ofNullable((T) completableFuture.getNow(null));
            }
        };
    }

    /**
     * Bind a publisher of the parts of the given name, as they arrive.
     *
     * @param factory     The form factory
     * @param formRequest The request, with a form body
     * @param context     The conversion context of the argument
     * @param partName    The name of the parts
     * @return The binding result
     */
    public BindingResult<T> bindPublisher(FormFactory factory,
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
                .flatMapSequential(raw -> ReactiveExecutionFlow.toPublisher(factory.completeFileUpload(formRequest, raw)))));
        } else if (CompletedAttribute.class.isAssignableFrom(elementType)) {
            flux = Flux.from(Publishers.bufferNow(Flux.from(completer.subscribeField(partName,
                        new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC_NO_BACKPRESSURE, argument)))
                    .flatMapSequential(raw -> ReactiveExecutionFlow.toPublisher(factory.completeAttribute(formRequest, raw)))))
                .doOnDiscard(CompletedAttribute.class, attr -> attr.closeAsync(factory.getDiskWriteExecutor()));
        } else if (CompletedPart.class.isAssignableFrom(elementType)) {
            flux = Flux.from(Publishers.bufferNow(Flux.from(completer.subscribeField(partName,
                        new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC_NO_BACKPRESSURE, argument)))
                    .flatMapSequential(raw -> ReactiveExecutionFlow.toPublisher(factory.completePart(formRequest, raw)))))
                .doOnDiscard(CompletedPart.class, part -> part.closeAsync(factory.getDiskWriteExecutor()));
        } else {
            @SuppressWarnings("unchecked")
            ArgumentConversionContext<Object> conversionContext = (ArgumentConversionContext<Object>) ConversionContext.of(elementArgument);
            flux = Flux.from(Publishers.bufferNow(Flux.from(completer.subscribeField(partName,
                        new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC_NO_BACKPRESSURE, argument)))
                    .flatMapSequential(raw -> ReactiveExecutionFlow.toPublisher(factory.completePart(formRequest, raw)))))
                .publishOn(Schedulers.fromExecutor(factory.getDiskWriteExecutor()))
                .concatMap(part -> Mono.justOrEmpty(convertCompletedPart(factory, conversionContext, part)));
        }

        Optional<T> converted = conversionService.convert(flux, context);
        T result = converted.orElseGet(() -> {
            @SuppressWarnings("unchecked")
            T castFlux = (T) flux;
            return castFlux;
        });
        return () -> Optional.of(result);
    }

    /**
     * Bind the field of the given name, once it was read whole.
     *
     * @param factory     The form factory
     * @param formRequest The request, with a form body
     * @param context     The conversion context of the argument
     * @param inputName   The name of the field
     * @return The binding result, pending until the field was read
     */
    public BindingResult<T> bindSingleValue(FormFactory factory,
                                             FormCapableHttpRequest<?> formRequest,
                                             ArgumentConversionContext<T> context,
                                             String inputName) {
        FormRouteCompleter completer = factory.getOrCreateCompleter(formRequest);
        CompletableFuture<Optional<T>> completableFuture = Mono.from(completer.subscribeField(inputName, new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.WAITS_FOR_FULL, context.getArgument())))
            .flatMap(rff -> Mono.from(ReactiveExecutionFlow.toPublisher(factory.completePart(formRequest, rff))))
            .map(d -> {
                boolean skipClose = false;
                try {
                    Optional<T> converted = conversionService.convert(d, context);
                    if (converted.isPresent() && converted.get() == d) {
                        skipClose = true;
                        if (formRequest instanceof LifecycleHttpRequest<?> lifecycleRequest) {
                            lifecycleRequest.addDisposalResource(() -> d.closeAsync(factory.getDiskWriteExecutor()));
                        }
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
                // a field the form does not have completes the future with null
                Optional<T> value = completableFuture.getNow(Optional.empty());
                return value == null ? Optional.empty() : value;
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

    private Charset resolveCharset(CompletedPart completedPart) {
        return Optional.ofNullable(completedPart.getMetadata().mediaType())
            .flatMap(MediaType::getCharset)
            .orElse(StandardCharsets.UTF_8);
    }

    private void closeRaw(SignalType signalType, io.micronaut.http.multipart.RawFormField raw) {
        if (signalType == SignalType.CANCEL || signalType == SignalType.ON_COMPLETE || signalType == SignalType.ON_ERROR) {
            raw.close();
        }
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
