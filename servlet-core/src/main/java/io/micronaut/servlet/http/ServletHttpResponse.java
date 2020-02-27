package io.micronaut.servlet.http;

import io.micronaut.http.MutableHttpResponse;
import org.reactivestreams.Publisher;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Models a serverless HTTP response, allowing access to the native response.
 *
 * @param <N> The native response type
 * @param <B> The body type
 * @author graemerocher
 * @since 2.0.0
 */
public interface ServletHttpResponse<N, B> extends MutableHttpResponse<B> {

    /**
     * @return The native response type.
     */
    N getNativeResponse();

    /**
     * Returns an {@link OutputStream} that can be used to write the body of the response.
     * This method is typically used to write binary data. If the body is text, the
     * {@link #getWriter()} method is more appropriate.
     *
     * @throws IOException if a valid {@link OutputStream} cannot be returned for some reason.
     * @throws IllegalStateException if {@link #getWriter} has already been called on this instance.
     * @return The output stream
     */
    OutputStream getOutputStream() throws IOException;

    /**
     * Returns a {@link BufferedWriter} that can be used to write the text body of the response.
     * If the written text will not be US-ASCII, you should specify a character encoding by calling
     * {@link #contentEncoding(CharSequence)} before calling this method.
     *
     * @throws IOException if a valid {@link BufferedWriter} cannot be returned for some reason.
     * @throws IllegalStateException if {@link #getOutputStream} has already been called on this
     *     instance.
     * @return The writer
     */
    BufferedWriter getWriter() throws IOException;

    /**
     * Streams data using the given data publisher.
     *
     * @param dataPublisher The data publisher
     * @return Emits the response once the stream has completed
     */
    default Publisher<MutableHttpResponse<?>> stream(Publisher<?> dataPublisher) {
        throw new UnsupportedOperationException("Data streaming not supported by implementation");
    }
}
