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

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.ConvertibleMultiValuesMap;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.ByteBody.SplitBackpressureMode;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.poja.util.QueryStringDecoder;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.ServletHttpResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;

/**
 * A base class for serverless POJA requests that provides a number of common methods
 * to be reused for body and binding.
 *
 * @param <B> The body type
 * @param <REQ> The POJA request type
 * @param <RES> The POJA response type
 * @author Andriy
 */
public abstract class PojaHttpRequest<B, REQ, RES>
        implements ServletHttpRequest<REQ, B>, ServerHttpRequest<B>, ServletExchange<REQ, RES>, MutableHttpRequest<B> {

    public static final Argument<ConvertibleValues> CONVERTIBLE_VALUES_ARGUMENT = Argument.of(ConvertibleValues.class);

    protected final ConversionService conversionService;
    protected final MediaTypeCodecRegistry codecRegistry;
    protected final MutableConvertibleValues<Object> attributes = new MutableConvertibleValuesMap<>();
    protected final PojaHttpResponse<?, RES> response;

    public PojaHttpRequest(
            ConversionService conversionService,
            MediaTypeCodecRegistry codecRegistry,
            PojaHttpResponse<?, RES> response
    ) {
        this.conversionService = conversionService;
        this.codecRegistry = codecRegistry;
        this.response = response;
    }

    @Override
    public abstract ByteBody byteBody();

    @Override
    public @NonNull MutableConvertibleValues<Object> getAttributes() {
        // Attributes are used for sharing internal data used by Micronaut logic.
        // We need to store them and provide when needed.
        return attributes;
    }

    /**
     * A utility method that allows consuming body.
     *
     * @return The result
     * @param <T> The function return value
     * @param consumer The method to consume the body
     */
    public <T> T consumeBody(Function<InputStream, T> consumer) {
        try (CloseableByteBody byteBody = byteBody().split(SplitBackpressureMode.FASTEST)) {
            return consumer.apply(byteBody.toInputStream());
        }
    }

    @Override
    public <T> @NonNull Optional<T> getBody(@NonNull ArgumentConversionContext<T> conversionContext) {
        Argument<T> arg = conversionContext.getArgument();
        if (arg == null) {
            return Optional.empty();
        }
        final Class<T> type = arg.getType();
        final MediaType contentType = getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);

        if (isFormSubmission()) {
            ConvertibleMultiValues<?> form = getFormData();
            if (ConvertibleValues.class == type || Object.class == type) {
                return Optional.of((T) form);
            } else {
                return conversionService.convert(form.asMap(), arg);
            }
        }

        final MediaTypeCodec codec = codecRegistry.findCodec(contentType, type).orElse(null);
        if (codec == null) {
            return Optional.empty();
        }
        if (ConvertibleValues.class == type || Object.class == type) {
            final Map map = consumeBody(inputStream -> codec.decode(Map.class, inputStream));
            ConvertibleValues result = ConvertibleValues.of(map);
            return Optional.of((T) result);
        } else {
            final T value = consumeBody(inputStream -> codec.decode(arg, inputStream));
            return Optional.of(value);
        }
    }

    protected ConvertibleMultiValues<?> getFormData() {
        return consumeBody(inputStream -> {
            try {
                String content = IOUtils.readText(new BufferedReader(new InputStreamReader(
                    inputStream, getCharacterEncoding()
                )));
                return parseFormData(content);
            } catch (IOException e) {
                throw new RuntimeException("Unable to parse body", e);
            }
        });
    }

    @Override
    public InputStream getInputStream() {
        return byteBody().split(SplitBackpressureMode.FASTEST).toInputStream();
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream()));
    }

    /**
     * Whether the request body is a form.
     *
     * @return Whether it is a form submission
     */
    public boolean isFormSubmission() {
        MediaType contentType = getContentType().orElse(null);
        return MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(contentType)
            || MediaType.MULTIPART_FORM_DATA_TYPE.equals(contentType);
    }

    @Override
    public ServletHttpRequest<REQ, ? super Object> getRequest() {
        return (ServletHttpRequest) this;
    }

    @Override
    public ServletHttpResponse<RES, ?> getResponse() {
        return response;
    }

    private ConvertibleMultiValues<CharSequence> parseFormData(String body) {
        Map parameterValues = new QueryStringDecoder(body, false).parameters();

        // Remove empty values
        Iterator<Entry<String, List<CharSequence>>> iterator = parameterValues.entrySet().iterator();
        while (iterator.hasNext()) {
            List<CharSequence> value = iterator.next().getValue();
            if (value.isEmpty() || StringUtils.isEmpty(value.get(0))) {
                iterator.remove();
            }
        }

        return new ConvertibleMultiValuesMap<CharSequence>(parameterValues, conversionService);
    }

}
