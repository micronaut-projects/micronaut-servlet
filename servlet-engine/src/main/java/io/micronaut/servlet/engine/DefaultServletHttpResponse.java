package io.micronaut.servlet.engine;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.io.Writable;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.servlet.http.ServletHttpResponse;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ServletHttpResponse} for the Servlet API.
 * @param <B> The body type
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public class DefaultServletHttpResponse<B> implements ServletHttpResponse<HttpServletResponse, B> {

    private final HttpServletResponse delegate;
    private final DefaultServletHttpRequest<?> request;
    private final ServletResponseHeaders headers;
    private B body;

    /**
     * Default constructor.
     * @param request The servlet request
     * @param delegate The servlet response
     */
    protected DefaultServletHttpResponse(
            DefaultServletHttpRequest request,
            HttpServletResponse delegate) {
        this.delegate = delegate;
        this.request = request;
        this.headers = new ServletResponseHeaders();
    }

    @Override
    public MutableHttpResponse<B> contentType(CharSequence contentType) {
        delegate.setContentType(
                Objects.requireNonNull(contentType, "Content type cannot be null").toString()
        );
        return this;
    }

    @Override
    public MutableHttpResponse<B> contentType(MediaType mediaType) {
        delegate.setContentType(
                Objects.requireNonNull(mediaType, "Content type cannot be null").toString()
        );
        return this;
    }

    @Override
    public MutableHttpResponse<B> contentLength(long length) {
        delegate.setContentLengthLong(length);
        return this;
    }

    @Override
    public MutableHttpResponse<B> locale(Locale locale) {
        Objects.requireNonNull(locale, "Locale cannot be null");
        delegate.setLocale(locale);
        return this;
    }

    @Override
    public MutableHttpResponse<B> header(CharSequence name, CharSequence value) {
        final String headerName = Objects.requireNonNull(name, "Header name cannot be null").toString();
        final String headerValue = Objects.requireNonNull(value, "Header value cannot be null").toString();
        delegate.addHeader(headerName, headerValue);
        return this;
    }

    @Override
    public MutableHttpResponse<B> status(int status) {
        delegate.setStatus(status);
        return this;
    }

    @Override
    public MutableHttpResponse<B> status(int status, CharSequence message) {
        if (message == null) {
            return status(status);
        }
        delegate.setStatus(status);
        return this;
    }

    @Override
    public MutableHttpResponse<B> status(HttpStatus status) {
        return status(Objects.requireNonNull(status, "status cannot be null").getCode());
    }

    @Override
    public HttpServletResponse getNativeResponse() {
        return delegate;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return delegate.getOutputStream();
    }

    @Override
    public BufferedWriter getWriter() throws IOException {
        return new BufferedWriter(delegate.getWriter());
    }

    @Override
    public MutableHttpResponse<B> cookie(Cookie cookie) {
        if (cookie instanceof ServletCookieAdapter) {
            delegate.addCookie(
                    ((ServletCookieAdapter) cookie).getCookie()
            );
        } else {

            final javax.servlet.http.Cookie c = new javax.servlet.http.Cookie(
                    cookie.getName(),
                    cookie.getValue());
            final String domain = cookie.getDomain();
            if (domain != null) {
                c.setDomain(domain);
            }
            final String path = cookie.getPath();
            if (path != null) {
                c.setPath(path);
            }
            c.setSecure(cookie.isSecure());
            c.setHttpOnly(cookie.isHttpOnly());
            c.setMaxAge((int) cookie.getMaxAge());
            delegate.addCookie(
                    c
            );
        }
        return this;
    }

    @Override
    public MutableHttpHeaders getHeaders() {
        return headers;
    }

    @Nonnull
    @Override
    public MutableConvertibleValues<Object> getAttributes() {
        return request;
    }

    @Nonnull
    @Override
    public Optional<B> getBody() {
        return Optional.ofNullable(body);
    }

    @Override
    public MutableHttpResponse<B> body(@Nullable B body) {
        if (body instanceof CharSequence) {
            if (!getContentType().isPresent()) {
                contentType(MediaType.TEXT_PLAIN_TYPE);
            }
        } else if (body != null) {
            getContentType().orElseGet(() -> {
                final Produces ann = body.getClass().getAnnotation(Produces.class);
                if (ann != null) {
                    final String[] v = ann.value();
                    if (ArrayUtils.isNotEmpty(v)) {
                        final MediaType mediaType = new MediaType(v[0]);
                        contentType(mediaType);
                        return mediaType;
                    }
                }
                if (!(body instanceof Writable)) {
                    contentType(MediaType.APPLICATION_JSON_TYPE);
                    return MediaType.APPLICATION_JSON_TYPE;
                } else {
                    return null;
                }
            });
        }
        this.body = body;
        return this;
    }

    @Override
    public MutableHttpResponse<B> status(HttpStatus status, CharSequence message) {
        Objects.requireNonNull(status, "Status cannot be null");
        if (message != null) {
            try {
                delegate.sendError(status.getCode(), message.toString());
            } catch (IOException e) {
                throw new InternalServerException("Error sending error code: " + e.getMessage(), e);
            }
        } else {
            delegate.setStatus(status.getCode());
        }
        return this;
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.valueOf(
                delegate.getStatus()
        );
    }

    /**
     * The response headers.
     */
    private class ServletResponseHeaders implements MutableHttpHeaders {

        @Override
        public MutableHttpHeaders add(CharSequence header, CharSequence value) {
            final String headerName =
                    Objects.requireNonNull(header, "Header name cannot be null").toString();

            final String headerValue =
                    Objects.requireNonNull(value, "Header value cannot be null").toString();

            delegate.setHeader(
                    headerName,
                    headerValue
            );
            return this;
        }

        @Override
        public MutableHttpHeaders remove(CharSequence header) {
            final String headerName = Objects.requireNonNull(header, "Header name cannot be null").toString();
            if (delegate.containsHeader(headerName)) {
                delegate.setHeader(headerName, "");
            }
            return this;
        }

        @Override
        public List<String> getAll(CharSequence name) {
            final Collection<String> values = delegate.getHeaders(
                    Objects.requireNonNull(name, "Header name cannot be null").toString()
            );
            if (values instanceof List) {
                return (List<String>) values;
            }
            return new ArrayList<>(values);
        }

        @Nullable
        @Override
        public String get(CharSequence name) {
            return delegate.getHeader(
                    Objects.requireNonNull(name, "Header name cannot be null").toString()
            );
        }

        @Override
        public Set<String> names() {
            final Collection<String> headerNames = delegate.getHeaderNames();
            if (headerNames instanceof Set) {
                return (Set<String>) headerNames;
            } else {
                return new HashSet<>(headerNames);
            }
        }

        @Override
        public Collection<List<String>> values() {
            return names()
                    .stream()
                    .map(this::getAll)
                    .collect(Collectors.toList());
        }

        @Override
        public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
            final String v = get(name);
            if (v != null) {
                return ConversionService.SHARED.convert(v, conversionContext);
            }
            return Optional.empty();
        }
    }
}
