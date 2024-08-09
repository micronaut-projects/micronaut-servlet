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
package io.micronaut.http.poja.llhttp;

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
import io.micronaut.http.poja.PojaHttpResponse;
import io.micronaut.http.simple.SimpleHttpHeaders;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.message.BasicHttpResponse;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Optional;

/**
 * An implementation of the POJA HTTP response based on Apache.
 *
 * @param <T> The body type
 * @author Andriy Dmytruk
 */
public class ApacheServletHttpResponse<T> extends PojaHttpResponse<T, ClassicHttpResponse> {

    private int code = HttpStatus.OK.getCode();
    private String reasonPhrase = HttpStatus.OK.getReason();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final SimpleHttpHeaders headers;
    private final MutableConvertibleValues<Object> attributes = new MutableConvertibleValuesMap<>();
    private T bodyObject;

    public ApacheServletHttpResponse(ConversionService conversionService) {
        this.headers = new SimpleHttpHeaders(conversionService);
    }

    @Override
    public ClassicHttpResponse getNativeResponse() {
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        headers.add(HttpHeaders.CONTENT_LENGTH, String.valueOf(out.size()));
        if ("chunked".equalsIgnoreCase(headers.get(HttpHeaders.TRANSFER_ENCODING))) {
            headers.remove(HttpHeaders.TRANSFER_ENCODING);
        }

        BasicClassicHttpResponse response = new BasicClassicHttpResponse(code, reasonPhrase);
        headers.forEachValue(response::addHeader);
        ContentType contentType = headers.getContentType().map(ContentType::parse)
            .orElse(ContentType.APPLICATION_JSON);
        ByteArrayEntity body = new ByteArrayEntity(out.toByteArray(), contentType);
        response.setEntity(body);
        return response;

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
    public MutableHttpResponse<T> status(int code, CharSequence message) {
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
