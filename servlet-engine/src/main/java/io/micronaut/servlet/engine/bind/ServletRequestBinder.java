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
import io.micronaut.servlet.http.ServletHttpRequest;

import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * A binder capable of binding the servlet request.
 *
 * @author graemerocher
 * @since 1.0.0
 */
public class ServletRequestBinder implements TypedRequestArgumentBinder<HttpServletRequest> {

    public static final Argument<HttpServletRequest> TYPE = Argument.of(HttpServletRequest.class);

    @Override
    public Argument<HttpServletRequest> argumentType() {
        return TYPE;
    }

    @Override
    public BindingResult<HttpServletRequest> bind(
            ArgumentConversionContext<HttpServletRequest> context,
            HttpRequest<?> source) {
        if (source instanceof ServletHttpRequest) {
            ServletHttpRequest servletRequest = (ServletHttpRequest) source;
            return () -> Optional.of((HttpServletRequest) servletRequest.getNativeRequest());
        }
        return BindingResult.UNSATISFIED;
    }
}
