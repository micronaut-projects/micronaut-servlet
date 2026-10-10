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
import io.micronaut.http.LifecycleHttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.PendingRequestBindingResult;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.CompletedAttribute;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.multipart.PartData;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.micronaut.http.reactive.execution.ReactiveExecutionFlow;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.binding.FormBinding;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.http.server.multipart.FormRouteCompleter;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.servlet.engine.DefaultServletHttpRequest;
import io.micronaut.servlet.engine.ServletParts;
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
    private final HttpServerConfiguration configuration;

    /**
     * Default constructor.
     *
     * @param conversionService   The conversion service.
     * @param formFactoryProvider The form factory provider.
     * @param messageBodyHandlerRegistry The message body handler registry
     */
    ServletPartBinder(ConversionService conversionService,
                      BeanProvider<FormFactory> formFactoryProvider,
                      MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                      HttpServerConfiguration configuration) {
        this.conversionService = conversionService;
        this.formFactoryProvider = formFactoryProvider;
        this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
        this.configuration = configuration;
    }

    @Override
    public Class<Part> getAnnotationType() {
        return Part.class;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        final String boundName = context.getAnnotationMetadata().stringValue(Part.class).orElse(context.getArgument().getName());
        // the request itself, or e.g. the mutable view of the request a filter continued with
        BindingResult<T> replaced = FormBinding.bindReplaced(context, source, conversionService, boundName);
        if (replaced != null) {
            // a filter set the body: the form it set, never the bytes of the request
            return replaced;
        }
        FormCapableHttpRequest<?> form = FormBinding.formRequest(source);
        FormFactory formFactory = form != null && form.hasFormBody() ? formFactoryProvider.get() : null;
        if (form != null && formFactory != null) {
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
                // the parts as they arrive, which the container only has once it parsed them all
                return bindPublisher(formFactory, form, context, boundName);
            }
            if (bodyStreamOpened(source)) {
                // e.g. a filter read a copy of the body: the container cannot parse the parts any more, so the
                // field is read from the form decoded from the byte body
                return bindSingleValue(formFactory, form, context, boundName);
            }
        }
        if (source instanceof ServletExchange<?, ?> exchange) {
            final HttpServletRequest nativeRequest = (HttpServletRequest) exchange.getRequest().getNativeRequest();
            final Argument<T> argument = context.getArgument();
            final String partName = boundName;
            final MediaType requestContentType = source.getContentType().orElse(null);
            final boolean isMultipart = requestContentType != null && requestContentType.matches(MediaType.MULTIPART_FORM_DATA_TYPE);

            if (!isMultipart && source instanceof FormCapableHttpRequest<?> && ((FormCapableHttpRequest<?>) source).hasFormBody()) {
                FormCapableHttpRequest<?> formRequest = (FormCapableHttpRequest<?>) source;
                BindingResult<T> result = bindFromForm(formRequest, context, partName);
                if (result != BindingResult.UNSATISFIED) {
                    return result;
                }
            }

            if (isMultipart) {
                return bindFromMultipart(context, exchange, nativeRequest, partName);
            }
        }
        return BindingResult.UNSATISFIED;
    }

    private static boolean bodyStreamOpened(HttpRequest<?> source) {
        return source instanceof ServletExchange<?, ?> exchange
            && exchange.getRequest() instanceof DefaultServletHttpRequest<?> servletRequest
            && servletRequest.isBodyStreamOpened();
    }

    private BindingResult<T> bindFromMultipart(ArgumentConversionContext<T> context,
                                               ServletExchange<?, ?> exchange,
                                               HttpServletRequest nativeRequest,
                                               String partName) {
        final Argument<T> argument = context.getArgument();
        final jakarta.servlet.http.Part part;
        try {
            part = ServletParts.part(nativeRequest, partName);
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
        // CompletedPart and CompletedFileUpload wrap the part itself, whatever its content type, so they are
        // resolved before a message body reader for that content type (a text/plain part would otherwise be
        // read as a String that cannot be converted to the argument type)
        if ((part.getSubmittedFileName() == null || part.getSubmittedFileName().isEmpty()) && CompletedFileUpload.class.isAssignableFrom(type)) {
            // a text field is not a file, answered like the form factory of the other runtimes
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Field [" + part.getName() + "] was expected to be a file upload, but is missing a file name");
        }
        if (CompletedPart.class.isAssignableFrom(type)) {
            try {
                @SuppressWarnings("java:S2095")
                CompletedFileUpload completedFileUpload = ServletCompletedFileUploadFactory.create(configuration, part);
                if (exchange.getRequest() instanceof LifecycleHttpRequest<?> lifecycleRequest) {
                    lifecycleRequest.addDisposalResource(() -> {
                        try {
                            completedFileUpload.close();
                        } catch (IOException ignored) {
                            // best effort cleanup
                        }
                    });
                }
                //noinspection unchecked
                return () -> (Optional<T>) Optional.of(completedFileUpload);
            } catch (IOException e) {
                throw new HttpStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to read part [" + partName + "]: " + e.getMessage()
                );
            }
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
        } else {
            Optional<T> converted = conversionService.convert(part, context);
            if (converted.isPresent()) {
                return () -> converted;
            }
        }
        return BindingResult.UNSATISFIED;
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

        return bindSingleValue(factory, formRequest, context, partName);
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

    private BindingResult<T> bindSingleValue(FormFactory factory,
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
        if (signalType == SignalType.CANCEL || signalType == SignalType.ON_COMPLETE || signalType == SignalType.ON_ERROR) {
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
