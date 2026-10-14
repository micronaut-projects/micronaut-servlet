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
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.http.BasicHttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpRequestWrapper;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.bind.binders.DefaultBodyAnnotationBinder;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.server.binding.ServerBodyAnnotationBinder;
import io.micronaut.http.server.binding.ServerRequestBody;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * The shared body binder of the server, which also keeps the body it decoded on a servlet request, so that
 * {@link HttpRequest#getBody()} returns it afterwards, e.g. to a filter after the route.
 *
 * @param <T> The body type
 * @author Denis Stepanov
 * @since 6.2.3
 */
@Internal
@Singleton
@Replaces(ServerBodyAnnotationBinder.class)
public class ServletServerBodyAnnotationBinder<T> extends ServerBodyAnnotationBinder<T> {

    private final MessageBodyHandlerRegistry bodyHandlerRegistry;
    private final BeanProvider<JsonMapper> jsonMapper;
    private volatile @Nullable ServletBodyBinder<T> detachedBinder;

    /**
     * @param conversionService   The conversion service
     * @param bodyHandlerRegistry The message body handler registry
     * @param formFactory         The form factory
     * @param jsonMapper          The JSON mapper, for a JSON stream read by the servlet body binder
     */
    public ServletServerBodyAnnotationBinder(ConversionService conversionService,
                                             MessageBodyHandlerRegistry bodyHandlerRegistry,
                                             BeanProvider<FormFactory> formFactory,
                                             BeanProvider<JsonMapper> jsonMapper) {
        super(conversionService, bodyHandlerRegistry, formFactory);
        this.bodyHandlerRegistry = bodyHandlerRegistry;
        this.jsonMapper = jsonMapper;
    }

    /**
     * The body of a binding outside the arguments of the route, e.g. the body an {@code AsyncRequestBody} decodes,
     * is read by the servlet body binder: when the shared binder buffers it, a body over the buffer limit fails the
     * buffering inside the shared buffer of the body, and the read then releases the body from that same call, which
     * the shared buffer of a non-Netty body does not support (micronaut-core, {@code ReactiveByteBufferByteBody}).
     *
     * @param context The conversion context
     * @param source  The request
     * @return The binding result
     */
    @Override
    public BindingResult<T> bindFullBody(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        if (source instanceof BasicHttpAttributes.DetachedBinding) {
            return servletBodyBinder().bind(context, source);
        }
        return super.bindFullBody(context, source);
    }

    /**
     * Every value of a field of a form for a collection, e.g. an argument {@code List<String> tag} of a URL-encoded
     * form, which the shared binder binds from the first field of that name only. The values are those of the
     * parameters of the servlet request, which include the fields of its form.
     *
     * @param context       The conversion context
     * @param source        The request
     * @param bodyComponent The name of the field
     * @return The binding result
     */
    @Override
    protected BindingResult<T> bindBodyPart(ArgumentConversionContext<T> context, HttpRequest<?> source, String bodyComponent) {
        Class<T> type = context.getArgument().getType();
        boolean collection = Iterable.class.isAssignableFrom(type) || type.isArray() && type != byte[].class;
        ServerHttpRequest<?> server = collection ? bodyOf(source) : null;
        if (server != null) {
            FormCapableHttpRequest<?> form = formRequest(server);
            if (form instanceof ServletHttpRequest<?, ?> servlet && form.hasFormBody()) {
                List<String> values = servlet.getParameters().getAll(bodyComponent);
                if (!values.isEmpty()) {
                    Optional<T> converted = conversionService.convert(values, context);
                    return () -> converted;
                }
            }
        }
        return super.bindBodyPart(context, source, bodyComponent);
    }

    /**
     * @return The servlet body binder, for the bodies the shared binder does not read for a servlet request
     */
    @SuppressWarnings("deprecation")
    ServletBodyBinder<T> servletBodyBinder() {
        ServletBodyBinder<T> binder = detachedBinder;
        if (binder == null) {
            binder = new ServletBodyBinder<>(conversionService, bodyHandlerRegistry, new DefaultBodyAnnotationBinder<>(conversionService), jsonMapper.get());
            detachedBinder = binder;
        }
        return binder;
    }

    /**
     * The server request whose bytes are the body, unless a filter set the body. Unlike the shared binder, this
     * does not ask {@link HttpRequest#getBody()}, which decodes the body of a servlet request rather than telling
     * whether a filter set it.
     *
     * @param source The request
     * @return The server request, or {@code null} if a filter set the body
     */
    @Override
    public @Nullable ServerHttpRequest<?> bodyOf(HttpRequest<?> source) {
        return ServerRequestBody.of(source);
    }

    /**
     * The servlet request under the views of filters, which decodes the form fields of the bytes given to it, e.g.
     * the copy a filter read. A view of it, such as the request a filter changed the URI of, may decode only its
     * own bytes.
     *
     * @param server The server request whose bytes are the body
     * @return The request that decodes the form
     */
    @Override
    protected @Nullable FormCapableHttpRequest<?> formRequest(ServerHttpRequest<?> server) {
        HttpRequest<?> current = server;
        while (true) {
            if (current instanceof ServletHttpRequest<?, ?> && current instanceof FormCapableHttpRequest<?> form) {
                return form;
            }
            if (current instanceof HttpRequestWrapper<?> wrapper) {
                current = wrapper.getDelegate();
            } else {
                return super.formRequest(server);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void cacheDecodedBody(HttpRequest<?> request, ServerHttpRequest<?> server, @Nullable Object value) {
        // only the body of the request itself, not of a view of it that a filter continued with
        if (value != null && request == server && server instanceof ParsedBodyHolder<?> holder) {
            ((ParsedBodyHolder<Object>) holder).setParsedBody(value);
        }
    }
}
