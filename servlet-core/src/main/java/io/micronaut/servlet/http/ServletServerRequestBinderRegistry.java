/*
 * Copyright 2017-2026 original authors
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
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.io.Readable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.NonBlockingBodyArgumentBinder;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.CompletedAttribute;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.multipart.RawFormField;
import io.micronaut.http.reactive.execution.ReactiveExecutionFlow;
import io.micronaut.http.server.binding.CompletableFutureBodyBinder;
import io.micronaut.http.server.binding.DefaultServerRequestBinderRegistry;
import io.micronaut.http.server.binding.FormBinding;
import io.micronaut.http.server.binding.InputStreamBodyBinder;
import io.micronaut.http.server.binding.PublisherBodyBinder;
import io.micronaut.http.server.binding.PublisherPartUploadBinder;
import io.micronaut.http.server.binding.ServerBodyAnnotationBinder;
import io.micronaut.http.server.binding.ServerRequestBody;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.http.server.multipart.FormRouteCompleter;
import io.micronaut.http.server.multipart.MultipartBody;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The binders of the server for the requests of the servlet runtimes: the shared binders of the server, with a
 * body {@link InputStream} that fails a read past {@code micronaut.server.max-request-size} with the
 * {@link ContentLengthExceededException} itself, answered with {@code 413 Request Entity Too Large}. Each runtime
 * declares the bean that replaces {@link DefaultServerRequestBinderRegistry}, with its own binders.
 *
 * @author Denis Stepanov
 * @since 6.2.3
 */
@Internal
public class ServletServerRequestBinderRegistry extends DefaultServerRequestBinderRegistry {

    /**
     * @param conversionService The conversion service
     * @param binders           The registered binders
     * @param bodyBinder        The body binder
     * @param formFactory       The form factory
     */
    @SuppressWarnings({"rawtypes", "java:S3740"}) // the binders of the registry of core
    public ServletServerRequestBinderRegistry(ConversionService conversionService,
                                              List<RequestArgumentBinder> binders,
                                              ServerBodyAnnotationBinder<Object> bodyBinder,
                                              BeanProvider<FormFactory> formFactory) {
        super(conversionService, binders, bodyBinder, formFactory);
        addArgumentBinder(new SizeLimitInputStreamBinder(new InputStreamBodyBinder(bodyBinder)));
        addArgumentBinder(new OrderedMultipartBodyBinder(formFactory));
        OrderedPublisherPartBinder publisherPartBinder = new OrderedPublisherPartBinder(new PublisherPartUploadBinder(conversionService, formFactory), formFactory);
        addArgumentBinder(publisherPartBinder);
        addArgumentBinder(new OrderedPublisherAnnotatedPartBinder(publisherPartBinder));
        addArgumentBinder(new CompletedPartBodyBinder(this));
        if (bodyBinder instanceof ServletServerBodyAnnotationBinder<Object> servletBodyBinder) {
            addArgumentBinder(new ServletBodyFallbackBinder<>(new CompletableFutureBodyBinder(bodyBinder), servletBodyBinder, true));
            addArgumentBinder(new ServletBodyFallbackBinder<>(new PublisherBodyBinder(bodyBinder, conversionService), servletBodyBinder, false));
            addArgumentBinder(new ReadableBodyBinder(servletBodyBinder));
        }
    }

    /**
     * A body the shared binder does not read like the servlet runtimes did, read by the servlet body binder: a
     * future of a form body, which the servlet body binder reads from the fields the container parsed (the shared
     * binder decodes the bytes of the form instead, and completes the future on the thread that read them, which
     * for a servlet container runs with the context of the request, so the stages of the route would see it), and
     * a future or a publisher of one property of the body, {@code @Body("name")}, which the shared binder binds to
     * the whole body.
     *
     * @param delegate   The shared binder, for any other body
     * @param bodyBinder The body binder
     * @param forms      Whether a form body is read by the servlet body binder
     * @param <B>        The type of the argument
     */
    private record ServletBodyFallbackBinder<B>(NonBlockingBodyArgumentBinder<B> delegate,
                                                ServletServerBodyAnnotationBinder<Object> bodyBinder,
                                                boolean forms) implements NonBlockingBodyArgumentBinder<B> {

        @Override
        public Argument<B> argumentType() {
            return delegate.argumentType();
        }

        @Override
        public List<Class<?>> superTypes() {
            return delegate.superTypes();
        }

        @Override
        @SuppressWarnings({"unchecked", "deprecation"})
        public BindingResult<B> bind(ArgumentConversionContext<B> context, HttpRequest<?> source) {
            boolean named = context.getAnnotationMetadata().stringValue(Body.class).isPresent();
            if ((named || forms && isForm(source)) && ServerRequestBody.of(source) != null) {
                return (BindingResult<B>) bodyBinder.servletBodyBinder()
                    .bind((ArgumentConversionContext<Object>) (ArgumentConversionContext<?>) context, source);
            }
            return delegate.bind(context, source);
        }

        private static boolean isForm(HttpRequest<?> source) {
            FormCapableHttpRequest<?> form = FormBinding.formRequest(source);
            return form != null && form.hasFormBody();
        }
    }

    /**
     * A {@code @Body Readable} argument, which reads the body of the servlet request as it arrives.
     *
     * @param bodyBinder The body binder
     */
    private record ReadableBodyBinder(ServletServerBodyAnnotationBinder<Object> bodyBinder) implements NonBlockingBodyArgumentBinder<Readable> {

        @Override
        public Argument<Readable> argumentType() {
            return Argument.of(Readable.class);
        }

        @Override
        @SuppressWarnings({"unchecked", "deprecation"})
        public BindingResult<Readable> bind(ArgumentConversionContext<Readable> context, HttpRequest<?> source) {
            return (BindingResult<Readable>) (BindingResult<?>) bodyBinder.servletBodyBinder()
                .bind((ArgumentConversionContext<Object>) (ArgumentConversionContext<?>) context, source);
        }
    }

    /**
     * A {@code @Body CompletedFileUpload} or {@code @Body CompletedPart} argument: the part of the multipart body
     * of the name of the argument, like an argument without annotation.
     *
     * @param registry The registry
     */
    private record CompletedPartBodyBinder(ServletServerRequestBinderRegistry registry)
        implements AnnotatedRequestArgumentBinder<Body, CompletedPart>, TypedRequestArgumentBinder<CompletedPart> {

        @Override
        public Class<Body> getAnnotationType() {
            return Body.class;
        }

        @Override
        public Argument<CompletedPart> argumentType() {
            return Argument.of(CompletedPart.class);
        }

        @Override
        @SuppressWarnings("unchecked")
        public BindingResult<CompletedPart> bind(ArgumentConversionContext<CompletedPart> context, HttpRequest<?> source) {
            Argument<CompletedPart> argument = context.getArgument();
            String name = argument.getAnnotationMetadata().stringValue(Body.class).orElse(argument.getName());
            Argument<CompletedPart> part = Argument.of(argument.getType(), name);
            return registry.findArgumentBinder(part)
                .map(binder -> binder.bind(context.with(part), source))
                .orElse(BindingResult.unsatisfied());
        }
    }

    /**
     * The completed parts of a {@code MultipartBody}, in the order of the form, as the parts of a servlet request
     * complete in any order.
     *
     * @param formFactory The form factory
     */
    private record OrderedMultipartBodyBinder(BeanProvider<FormFactory> formFactory) implements NonBlockingBodyArgumentBinder<MultipartBody> {

        @Override
        public Argument<MultipartBody> argumentType() {
            return Argument.of(MultipartBody.class);
        }

        @Override
        public BindingResult<MultipartBody> bind(ArgumentConversionContext<MultipartBody> context, HttpRequest<?> source) {
            // the request itself, or e.g. the mutable view of the request that a filter continued with
            FormCapableHttpRequest<?> form = FormBinding.formRequest(source);
            if (form == null || !form.hasFormBody()) {
                return BindingResult.empty();
            }
            FormFactory factory = formFactory.get();
            Flux<? extends CompletedPart> parts = Flux.from(form.getRawFormFields())
                .flatMapSequential(raw -> ReactiveExecutionFlow.toPublisher(factory.completePart(form, raw)))
                .doOnDiscard(CompletedPart.class, part -> part.closeAsync(factory.getDiskWriteExecutor()));
            MultipartBody body = parts::subscribe;
            return () -> Optional.of(body);
        }
    }

    /**
     * A publisher of the completed uploads of one name, in the order of the form, as the parts of a servlet request
     * complete in any order. Any other publisher of parts is bound by the shared binder.
     *
     * @param delegate    The shared binder
     * @param formFactory The form factory
     */
    private record OrderedPublisherPartBinder(PublisherPartUploadBinder delegate, BeanProvider<FormFactory> formFactory)
        implements TypedRequestArgumentBinder<Publisher<?>> {

        @Override
        public Argument<Publisher<?>> argumentType() {
            return delegate.argumentType();
        }

        @Override
        public BindingResult<Publisher<?>> bind(ArgumentConversionContext<Publisher<?>> context, HttpRequest<?> source) {
            Argument<Publisher<?>> argument = context.getArgument();
            Class<?> element = argument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT).getType();
            FormCapableHttpRequest<?> form = FormBinding.formRequest(source);
            boolean completed = element == CompletedFileUpload.class || element == CompletedAttribute.class || element == CompletedPart.class;
            if (!completed || form == null || !form.hasFormBody()) {
                return delegate.bind(context, source);
            }
            String name = argument.getAnnotationMetadata().stringValue(Bindable.NAME).orElse(argument.getName());
            FormFactory factory = formFactory.get();
            // these objects consume little memory, and accept writing to disk anyway, so they are subscribed to eagerly
            Flux<RawFormField> fields = Flux.from(factory.getOrCreateCompleter(form).subscribeField(name,
                new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.ASYNC_NO_BACKPRESSURE, argument)));
            Flux<?> parts;
            if (element == CompletedFileUpload.class) {
                parts = fields.flatMapSequential(raw -> ReactiveExecutionFlow.toPublisher(factory.completeFileUpload(form, raw)));
            } else if (element == CompletedAttribute.class) {
                parts = fields.flatMapSequential(raw -> ReactiveExecutionFlow.toPublisher(factory.completeAttribute(form, raw)));
            } else {
                parts = fields.flatMapSequential(raw -> ReactiveExecutionFlow.toPublisher(factory.completePart(form, raw)));
            }
            Publisher<?> publisher = Flux.from(Publishers.bufferNow(parts))
                .doOnDiscard(CompletedPart.class, part -> part.closeAsync(factory.getDiskWriteExecutor()));
            Publisher<?> converted = Publishers.convertPublisher(ConversionService.SHARED, publisher, argument.getType());
            return () -> Optional.of(converted);
        }
    }

    /**
     * {@link OrderedPublisherPartBinder} for an argument annotated with {@link Part}.
     *
     * @param delegate The binder
     */
    private record OrderedPublisherAnnotatedPartBinder(OrderedPublisherPartBinder delegate)
        implements AnnotatedRequestArgumentBinder<Part, Publisher<?>>, TypedRequestArgumentBinder<Publisher<?>> {

        @Override
        public Class<Part> getAnnotationType() {
            return Part.class;
        }

        @Override
        public Argument<Publisher<?>> argumentType() {
            return delegate.argumentType();
        }

        @Override
        public BindingResult<Publisher<?>> bind(ArgumentConversionContext<Publisher<?>> context, HttpRequest<?> source) {
            return delegate.bind(context, source);
        }
    }

    /**
     * The body {@link InputStream} of the shared binder, failing a read past the size limit with
     * {@link ContentLengthExceededException} rather than the {@link IOException} that wraps it.
     *
     * @param delegate The binder of the body stream
     */
    private record SizeLimitInputStreamBinder(InputStreamBodyBinder delegate) implements NonBlockingBodyArgumentBinder<InputStream> {

        @Override
        public Argument<InputStream> argumentType() {
            return InputStreamBodyBinder.TYPE;
        }

        @Override
        public BindingResult<InputStream> bind(ArgumentConversionContext<InputStream> context, HttpRequest<?> source) {
            BindingResult<InputStream> result = delegate.bind(context, source);
            Optional<InputStream> stream = result.getValue();
            if (stream.isEmpty()) {
                return result;
            }
            InputStream limited = new SizeLimitInputStream(stream.get());
            return () -> Optional.of(limited);
        }
    }

    /**
     * Fails a read past {@code micronaut.server.max-request-size} with the {@link ContentLengthExceededException}
     * itself, so that a route reading the body answers {@code 413 Request Entity Too Large} and not {@code 500}.
     */
    private static final class SizeLimitInputStream extends FilterInputStream {

        SizeLimitInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (IOException e) {
                throw tooLargeOr(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return in.read(b, off, len);
            } catch (IOException e) {
                throw tooLargeOr(e);
            }
        }

        private static IOException tooLargeOr(IOException e) {
            for (Throwable cause = e.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
                if (cause instanceof ContentLengthExceededException tooLarge) {
                    throw tooLarge;
                }
            }
            return e;
        }
    }
}
