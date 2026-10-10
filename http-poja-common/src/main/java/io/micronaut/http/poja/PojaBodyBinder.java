/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.http.poja;

import io.micronaut.context.BeanProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.DefaultBodyAnnotationBinder;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.reactive.execution.ReactiveExecutionFlow;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.http.server.multipart.MultipartBody;
import io.micronaut.json.JsonMapper;
import io.micronaut.servlet.http.ServletBodyBinder;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * A {@link ServletBodyBinder} specialization used for POJA serverless requests.
 *
 * @param <T> The body type
 * @since 6.0.0
 */
@Internal
final class PojaBodyBinder<T> extends ServletBodyBinder<T> {

    private final BeanProvider<FormFactory> formFactoryProvider;

    @SuppressWarnings("unchecked")
    PojaBodyBinder(ConversionService conversionService,
                   MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                   DefaultBodyAnnotationBinder<?> defaultBodyAnnotationBinder,
                   JsonMapper jsonMapper,
                   BeanProvider<FormFactory> formFactoryProvider) {
        super(conversionService, messageBodyHandlerRegistry, (DefaultBodyAnnotationBinder<T>) defaultBodyAnnotationBinder, jsonMapper);
        this.formFactoryProvider = formFactoryProvider;
    }

    @Override
    @SuppressWarnings("unchecked")
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        if (context.getArgument().getType() == MultipartBody.class && source instanceof FormCapableHttpRequest<?> form && form.hasFormBody()) {
            // the parts as they complete, like the Netty server
            FormFactory formFactory = formFactoryProvider.get();
            Flux<? extends CompletedPart> parts = Flux.from(form.getRawFormFields())
                .flatMapSequential(raw -> ReactiveExecutionFlow.toPublisher(formFactory.completePart(form, raw)))
                .doOnDiscard(CompletedPart.class, part -> part.closeAsync(formFactory.getDiskWriteExecutor()));
            MultipartBody body = parts::subscribe;
            return () -> (Optional<T>) Optional.of(body);
        }
        return super.bind(context, source);
    }
}
