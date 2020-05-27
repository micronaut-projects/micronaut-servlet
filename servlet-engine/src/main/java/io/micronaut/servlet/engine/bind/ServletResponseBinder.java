/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.engine.bind;

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.servlet.http.ServletExchange;

import javax.servlet.http.HttpServletResponse;
import java.util.Optional;

/**
 * A binder capable of binding the servlet response.
 *
 * @author graemerocher
 * @since 1.0.0
 *
 */
public class ServletResponseBinder implements TypedRequestArgumentBinder<HttpServletResponse> {

    public static final Argument<HttpServletResponse> TYPE = Argument.of(HttpServletResponse.class);

    @Override
    public Argument<HttpServletResponse> argumentType() {
        return TYPE;
    }

    @Override
    public BindingResult<HttpServletResponse> bind(ArgumentConversionContext<HttpServletResponse> context, HttpRequest<?> source) {
        if (source instanceof ServletExchange) {
            ServletExchange servletRequest = (ServletExchange) source;
            return () -> Optional.of((HttpServletResponse) servletRequest.getResponse().getNativeResponse());
        }
        return BindingResult.UNSATISFIED;
    }
}
