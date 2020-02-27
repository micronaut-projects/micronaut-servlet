package io.micronaut.servlet.http;

import io.micronaut.http.HttpMessage;
import org.reactivestreams.Publisher;

/**
 * Represents a streamed HTTP message.
 * @author graemerocher
 * @since 1.0.0
 * @param <B> The body type
 * @param <BB> The byte buffer type
 */
public interface StreamedServletMessage<B, BB> extends HttpMessage<B>, Publisher<BB> {
}
