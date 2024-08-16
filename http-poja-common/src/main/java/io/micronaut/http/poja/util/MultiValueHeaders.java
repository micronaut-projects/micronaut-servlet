package io.micronaut.http.poja.util;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleMultiValuesMap;
import io.micronaut.http.MutableHttpHeaders;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Headers implementation based on a multi-value map.
 * The implementation performs the header's standardization.
 *
 * @param headers The values
 */
public record MultiValueHeaders(
    MutableConvertibleMultiValuesMap<String> headers
) implements MutableHttpHeaders {

    public MultiValueHeaders(Map<String, List<String>> headers, ConversionService conversionService) {
        this(standardizeHeaders(headers, conversionService));
    }

    @Override
    public List<String> getAll(CharSequence name) {
        return headers.getAll(standardizeHeader(name));
    }

    @Override
    public @Nullable String get(CharSequence name) {
        return headers.get(standardizeHeader(name));
    }

    @Override
    public Set<String> names() {
        return headers.names();
    }

    @Override
    public Collection<List<String>> values() {
        return headers.values();
    }

    @Override
    public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
        return headers.get(standardizeHeader(name), conversionContext);
    }

    @Override
    public MutableHttpHeaders add(CharSequence header, CharSequence value) {
        headers.add(standardizeHeader(header), value == null ? null : value.toString());
        return this;
    }

    @Override
    public MutableHttpHeaders remove(CharSequence header) {
        headers.remove(standardizeHeader(header));
        return this;
    }

    @Override
    public void setConversionService(@NonNull ConversionService conversionService) {
        this.headers.setConversionService(conversionService);
    }

    private static MutableConvertibleMultiValuesMap<String> standardizeHeaders(
        Map<String, List<String>> headers, ConversionService conversionService
    ) {
        MutableConvertibleMultiValuesMap<String> map
            = new MutableConvertibleMultiValuesMap<>(new HashMap<>(), conversionService);
        for (String key: headers.keySet()) {
            map.put(standardizeHeader(key), headers.get(key));
        }
        return map;
    }

    private static String standardizeHeader(CharSequence charSequence) {
        String s;
        if (charSequence == null) {
            return null;
        } else if (charSequence instanceof String) {
            s = (String) charSequence;
        } else {
            s = charSequence.toString();
        }

        StringBuilder result = new StringBuilder(s.length());
        boolean upperCase = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (upperCase && 'a' <= c && c <= 'z') {
                c = (char) (c - 32);
            } else if (!upperCase && 'A' <= c && c <= 'Z') {
                c = (char) (c + 32);
            }
            result.append(c);
            upperCase = c == '-';
        }
        return result.toString();
    }
}
