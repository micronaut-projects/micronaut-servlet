package io.micronaut.servlet.http;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal class that represents an {@link ServletHttpRequest} that includes the body already decoded.
 *
 * @param <N> The native request type
 * @param <B> The body type
 * @author graemerocher
 * @since 2.0.0
 */
@Internal
final class ServletRequestAndBody<N, B> extends HttpRequestWrapper<B> implements ServletHttpRequest<N, B> {

    private final Argument<B> bodyType;

    /**
     * @param delegate The Http Request
     * @param bodyType     The body, never null
     */
    ServletRequestAndBody(ServletHttpRequest<N, B> delegate, Argument<B> bodyType) {
        super(delegate);
        this.bodyType = Objects.requireNonNull(bodyType, "Body type cannot be null");
    }

    @Override
    public boolean isAsyncSupported() {
        return ((ServletHttpRequest<N, B>) getDelegate()).isAsyncSupported();
    }

    @Override
    public Optional<B> getBody() {
        return getBody(bodyType);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return ((ServletHttpRequest<N, B>) getDelegate()).getInputStream();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return ((ServletHttpRequest<N, B>) getDelegate()).getReader();
    }

    @Override
    public N getNativeRequest() {
        return ((ServletHttpRequest<N, B>) getDelegate()).getNativeRequest();
    }
}
