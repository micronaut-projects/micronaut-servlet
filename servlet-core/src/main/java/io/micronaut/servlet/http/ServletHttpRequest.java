package io.micronaut.servlet.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import org.reactivestreams.Publisher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;

/**
 * Interface that models a serverless request which typically support blocking I/O.
 *
 * @param <N> The native request type
 * @param <B> The body type
 * @author graemerocher
 * @since 2.0.0
 */
public interface ServletHttpRequest<N, B> extends HttpRequest<B> {
    /**
     * Returns an {@link InputStream} that can be used to read the body of this HTTP request.
     * This method is typically used to read binary data. If the body is text, the
     * {@link #getReader()} method is more appropriate.
     *
     * @return The input stream
     * @throws IOException           if a valid {@link InputStream} cannot be returned for some reason.
     * @throws IllegalStateException if {@link #getReader()} has already been called on this instance.
     */
    InputStream getInputStream() throws IOException;

    /**
     * Returns a {@link BufferedReader} that can be used to read the text body of this HTTP request.
     *
     * @return The reader
     * @throws IOException           if a valid {@link BufferedReader} cannot be returned for some reason.
     * @throws IllegalStateException if {@link #getInputStream()} has already been called on this
     *                               instance.
     */
    BufferedReader getReader() throws IOException;

    /**
     * @return The native request type
     */
    N getNativeRequest();

    /**
     * Checks if this request supports asynchronous operation.
     *
     * <p>Asynchronous operation is disabled for this request if this request
     * is within the scope of a filter or servlet that has not been annotated
     * or flagged in the deployment descriptor as being able to support
     * asynchronous handling.
     *
     * @return true if this request supports asynchronous operation, false
     * otherwise
     *
     * @since Servlet 3.0
     */
    default boolean isAsyncSupported() {
        return false;
    }

    /**
     * Causes the container to dispatch a thread, possibly from a managed
     * thread pool, to run the specified <tt>Runnable</tt>. The container may
     * propagate appropriate contextual information to the <tt>Runnable</tt>.
     *
     * @param responsePublisher The response publisher
     */
    default Publisher<? extends MutableHttpResponse<?>> subscribeOnExecutor(Publisher<? extends MutableHttpResponse<?>> responsePublisher) {
        throw new UnsupportedOperationException("Asynchronous processing is not supported");
    }

}
