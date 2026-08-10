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
package io.micronaut.http.poja.apache;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.poja.PojaHttpResponse;
import io.micronaut.http.simple.SimpleHttpHeaders;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * An implementation of the POJA HTTP response based on Apache.
 *
 * @param <T> The body type
 * @author Andriy Dmytruk
 * @since 4.10.0
 */
@Internal
final class ApacheServletHttpResponse<T> extends PojaHttpResponse<T, ClassicHttpResponse> {

    private final ApacheResponseContext responseContext;

    private int code = HttpStatus.OK.getCode();
    private String reasonPhrase = HttpStatus.OK.getReason();

    private final SimpleHttpHeaders headers;
    private final MutableConvertibleValues<Object> attributes = new MutableConvertibleValuesMap<>();
    private @Nullable T bodyObject;

    /**
     * Create an Apache-based response.
     *
     * @param conversionService The conversion service
     */
    ApacheServletHttpResponse(ApacheResponseContext responseContext, ConversionService conversionService) {
        this.responseContext = responseContext;
        this.headers = new SimpleHttpHeaders(conversionService);
        responseContext.primaryResponse = this;
    }

    @Override
    public ClassicHttpResponse getNativeResponse() {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(code, reasonPhrase);
        headers.forEachValue(response::addHeader);
        return response;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return responseContext.commit(getNativeResponse());
    }

    @Override
    public BufferedWriter getWriter() throws IOException {
        return new BufferedWriter(new OutputStreamWriter(getOutputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public MutableHttpResponse<T> cookie(Cookie cookie) {
        return this;
    }

    @Override
    public <B> MutableHttpResponse<B> body(@Nullable B body) {
        this.bodyObject = (T) body;
        return (MutableHttpResponse<B>) this;
    }

    @NonNull
    @Override
    public Optional<T> getBody() {
        return Optional.ofNullable(bodyObject);
    }

    @Override
    public MutableHttpResponse<T> status(int code, @Nullable CharSequence message) {
        this.code = code;
        if (message == null) {
            this.reasonPhrase = HttpStatus.getDefaultReason(code);
        } else {
            this.reasonPhrase = message.toString();
        }
        return this;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String reason() {
        return reasonPhrase;
    }

    @Override
    public MutableHttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public @NonNull MutableConvertibleValues<Object> getAttributes() {
        return attributes;
    }

}
