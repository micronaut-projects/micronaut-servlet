package io.micronaut.http.poja.util;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleMultiValuesMap;
import io.micronaut.http.MutableHttpParameters;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Query parameters implementation.
 *
 * @param queryParams The values
 */
public record MultiValuesQueryParameters(
    MutableConvertibleMultiValuesMap<String> queryParams
) implements MutableHttpParameters {

    /**
     * Construct the query parameters.
     *
     * @param parameters The parameters as a map.
     * @param conversionService The conversion service.
     */
    public MultiValuesQueryParameters(
            Map<CharSequence, List<String>> parameters,
            ConversionService conversionService
    ) {
        this(new MutableConvertibleMultiValuesMap<>(parameters, conversionService));
    }

    @Override
    public List<String> getAll(CharSequence name) {
        return queryParams.getAll(name);
    }

    @Override
    public @Nullable String get(CharSequence name) {
        return queryParams.get(name);
    }

    @Override
    public Set<String> names() {
        return queryParams.names();
    }

    @Override
    public Collection<List<String>> values() {
        return queryParams.values();
    }

    @Override
    public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
        return queryParams.get(name, conversionContext);
    }

    @Override
    public MutableHttpParameters add(CharSequence name, List<CharSequence> values) {
        for (CharSequence value: values) {
            queryParams.add(name, value == null ? null : value.toString());
        }
        return this;
    }

    @Override
    public void setConversionService(@NonNull ConversionService conversionService) {
        queryParams.setConversionService(conversionService);
    }

}
