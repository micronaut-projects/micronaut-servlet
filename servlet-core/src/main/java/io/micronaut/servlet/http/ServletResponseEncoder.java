package io.micronaut.servlet.http;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Indexed;
import io.micronaut.http.MutableHttpResponse;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;

/**
 * An interface for custom encoding of the HTTP response.
 *
 * @author graemerocher
 * @since 1.0.0
 * @param <T> The response type
 */
@Indexed(ServletResponseEncoder.class)
public interface ServletResponseEncoder<T> {

    /**
     * @return The response type.
     */
    Class<T> getResponseType();

    /**
     * Encode the given value.
     * @param exchange The change
     * @param annotationMetadata The annotation metadata declared on the method
     * @param value The value to encode
     * @return A publisher that emits completes with the response once the value has been encoded
     */
    Publisher<MutableHttpResponse<?>> encode(
            @Nonnull ServletExchange<?, ?> exchange,
            AnnotationMetadata annotationMetadata,
            @Nonnull T value);
}
