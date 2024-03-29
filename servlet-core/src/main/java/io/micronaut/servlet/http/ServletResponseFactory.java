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
package io.micronaut.servlet.http;

import io.micronaut.core.io.service.ServiceDefinition;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponseFactory;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.http.simple.SimpleHttpResponseFactory;

import java.util.Objects;

/**
 * An implementation of the {@link HttpResponseFactory} case that retrieves the
 * response object from the current request bound to the current thread.
 *
 * @author graemerocher
 * @since 2.0.0
 */
public class ServletResponseFactory implements HttpResponseFactory {
    private static final HttpResponseFactory ALTERNATE;

    static {
        final SoftServiceLoader<HttpResponseFactory> factories = SoftServiceLoader.load(HttpResponseFactory.class);
        HttpResponseFactory alternate = null;
        for (ServiceDefinition<HttpResponseFactory> factory : factories) {
            if (factory.isPresent() && !factory.getName().equals(ServletResponseFactory.class.getName())) {
                alternate = factory.load();
                break;
            }
        }

        ALTERNATE = Objects.requireNonNullElseGet(alternate, SimpleHttpResponseFactory::new);
    }

    @Override
    public <T> MutableHttpResponse<T> ok(T body) {
        final HttpRequest<Object> req = ServerRequestContext.currentRequest().orElse(null);
        if (req instanceof ServletExchange<?, ?> servletExchange) {
            return servletExchange.getResponse().status(HttpStatus.OK).body(body);
        } else {
            return ALTERNATE.ok(body);
        }
    }

    @Override
    public <T> MutableHttpResponse<T> status(HttpStatus status, String reason) {
        return status(status.getCode(), reason);
    }

    @Override
    public <T> MutableHttpResponse<T> status(int status, String reason) {
        final HttpRequest<Object> req = ServerRequestContext.currentRequest().orElse(null);
        if (req instanceof ServletExchange<?, ?> servletExchange) {
            return (MutableHttpResponse<T>) servletExchange.getResponse().status(status, reason);
        } else {
            return ALTERNATE.status(status, reason);
        }
    }

    @Override
    public <T> MutableHttpResponse<T> status(HttpStatus status, T body) {
        final HttpRequest<Object> req = ServerRequestContext.currentRequest().orElse(null);
        if (req instanceof ServletExchange<?, ?> servletExchange) {
            return servletExchange.getResponse().status(status).body(body);
        } else {
            return ALTERNATE.status(status, body);
        }
    }
}
