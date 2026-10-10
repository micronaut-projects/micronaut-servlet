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
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.io.Readable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.LifecycleHttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.servlet.engine.DefaultServletHttpRequest;
import io.micronaut.servlet.engine.ServletParts;
import io.micronaut.servlet.http.FormPartBinder;
import io.micronaut.servlet.http.ServletExchange;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
    private final FormPartBinder<T> formPartBinder;

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
        this.formPartBinder = new FormPartBinder<>(conversionService, formFactoryProvider);
    }

    @Override
    public Class<Part> getAnnotationType() {
        return Part.class;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        final String boundName = context.getAnnotationMetadata().stringValue(Part.class).orElse(context.getArgument().getName());
        BindingResult<T> fromForm = formPartBinder.bindForm(context, source, boundName, () -> bodyStreamOpened(source));
        if (fromForm != null) {
            return fromForm;
        }
        ServletExchange<?, ?> exchange = DefaultServletHttpRequest.exchangeOf(source);
        if (exchange != null) {
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
        return DefaultServletHttpRequest.exchangeOf(source) instanceof DefaultServletHttpRequest<?> servletRequest
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
            return formPartBinder.bindPublisher(factory, formRequest, context, partName);
        }

        return formPartBinder.bindSingleValue(factory, formRequest, context, partName);
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

    private BufferedReader newReader(jakarta.servlet.http.Part part) throws IOException {
        final Charset charset = Optional.ofNullable(part.getContentType())
            .map(MediaType::new)
            .flatMap(MediaType::getCharset)
            .orElse(StandardCharsets.UTF_8);
        final InputStreamReader inputStreamReader = new InputStreamReader(part.getInputStream(), charset);
        return new BufferedReader(inputStreamReader);
    }
}
