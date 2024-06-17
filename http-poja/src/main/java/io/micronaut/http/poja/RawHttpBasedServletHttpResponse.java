/*
 * Copyright © 2024 Oracle and/or its affiliates.
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

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.servlet.http.ServletHttpResponse;
import rawhttp.core.HttpVersion;
import rawhttp.core.RawHttpHeaders;
import rawhttp.core.RawHttpResponse;
import rawhttp.core.StatusLine;
import rawhttp.core.body.EagerBodyReader;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Optional;

/**
 * @author Sahoo.
 */
class RawHttpBasedServletHttpResponse implements ServletHttpResponse<RawHttpResponse<Void>, String> {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private int code = HttpStatus.OK.getCode();

    private String reason = HttpStatus.OK.getReason();

    private String body;
    private final SimpleHttpHeaders headers;

    private final MutableConvertibleValues<Object> attributes = new MutableConvertibleValuesMap<>();

    public RawHttpBasedServletHttpResponse(ConversionService conversionService) {
        this.headers = new SimpleHttpHeaders(conversionService);
    }

    @Override
    public RawHttpResponse<Void> getNativeResponse() {
        headers.add(HttpHeaders.CONTENT_LENGTH, String.valueOf(out.size()));
        return new RawHttpResponse<>(null,
                null,
                new StatusLine(HttpVersion.HTTP_1_1, code, reason),
                toRawHttpheaders(),
                new EagerBodyReader(out.toByteArray()));
    }

    private RawHttpHeaders toRawHttpheaders() {
        RawHttpHeaders.Builder builder = RawHttpHeaders.newBuilder();
        headers.forEachValue(builder::with);
        return builder.build();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return out;
    }

    @Override
    public BufferedWriter getWriter() throws IOException {
        return new BufferedWriter(new PrintWriter(out));
    }

    @Override
    public MutableHttpResponse<String> cookie(Cookie cookie) {
        return this;
    }

    @Override
    public <T> MutableHttpResponse<T> body(@Nullable T body) {
        // TODO
        throw new UnsupportedOperationException("TBD");
    }

    @Override
    public MutableHttpResponse<String> status(int code, CharSequence message) {
        this.code = code;
        if (message == null) {
            this.reason = HttpStatus.getDefaultReason(code);
        } else {
            this.reason = message.toString();
        }
        return this;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public MutableHttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public @NonNull MutableConvertibleValues<Object> getAttributes() {
        return attributes;
    }

    @Override
    public @NonNull Optional<String> getBody() {
        return Optional.ofNullable(body);
    }
}
