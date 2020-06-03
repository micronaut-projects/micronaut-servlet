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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.http.codec.MediaTypeCodecRegistry;

import java.util.Optional;

/**
 * A {@link io.micronaut.http.bind.binders.RequestArgumentBinder} that can bind the HTTP request
 * for a {@link ServletHttpRequest} including resolving any type arguments for the body.
 *
 * @author graemerocher
 * @since 2.0.0
 */
@Internal
class ServletRequestBinder implements TypedRequestArgumentBinder<HttpRequest> {

    private final MediaTypeCodecRegistry mediaTypeCodecRegistry;

    /**
     * Default constructor.
     *
     * @param mediaTypeCodecRegistry The media type code registry
     */
    ServletRequestBinder(MediaTypeCodecRegistry mediaTypeCodecRegistry) {
        this.mediaTypeCodecRegistry = mediaTypeCodecRegistry;
    }

    @Override
    public Argument<HttpRequest> argumentType() {
        return Argument.of(HttpRequest.class);
    }

    @Override
    public BindingResult<HttpRequest> bind(ArgumentConversionContext<HttpRequest> context, HttpRequest<?> source) {
        if (source instanceof ServletHttpRequest) {
            ServletHttpRequest<?, ?> serverlessHttpRequest = (ServletHttpRequest<?, ?>) source;
            long contentLength = serverlessHttpRequest.getContentLength();
            final Argument<?> bodyType = context.getArgument().getFirstTypeVariable().orElse(null);
            if (bodyType != null && contentLength != 0) {
                return () -> Optional.of(
                        new ServletRequestAndBody(serverlessHttpRequest, bodyType)
                );
            }

        }
        return () -> Optional.of(source);
    }
}
