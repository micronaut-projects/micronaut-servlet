/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.servlet.engine;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.io.buffer.ReferenceCounted;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.servlet.http.ServletHttpResponse;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ServletHttpResponse} for the Servlet API.
 * @param <B> The body type
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public class DefaultServletHttpResponse<B> implements ServletHttpResponse<HttpServletResponse, B> {

    private static final byte[] EMPTY_ARRAY = "[]".getBytes();

    private final HttpServletResponse delegate;
    private final DefaultServletHttpRequest<?> request;
    private final ServletResponseHeaders headers;
    private B body;
    private int status = HttpStatus.OK.getCode();
    private String reason = HttpStatus.OK.getReason();

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
    public Publisher<MutableHttpResponse<?>> stream(Publisher<?> dataPublisher) {
        return Flux.create(emitter -> dataPublisher.subscribe(new Subscriber<Object>() {
            ServletOutputStream outputStream;
            Subscription subscription;
            final AtomicBoolean finished = new AtomicBoolean();
            MediaType contentType = getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
            MediaTypeCodec codec = request.getCodecRegistry().findCodec(contentType).orElse(null);
            boolean isJson = contentType.getSubtype().equals("json");
            boolean first = true;
            boolean raw = false;
            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                delegate.setHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
                try {
                    outputStream = delegate.getOutputStream();
                    outputStream.setWriteListener(new WriteListener() {
                        @Override
                        public void onWritePossible() {
                            s.request(1);
                        }

                        @Override
                        public void onError(Throwable t) {
                            emitter.error(t);
                        }
                    });
                } catch (IOException e) {
                    if (finished.compareAndSet(false, true)) {
                        emitter.error(e);
                        subscription.cancel();
                    }
                }
            }

            @Override
            public void onNext(Object o) {
                try {
                    if (outputStream.isReady() && !finished.get()) {

                        if (o instanceof byte[]) {
                            raw = true;
                            outputStream.write((byte[]) o);
                            flushIfReady();
                        } else if (o instanceof ByteBuffer) {
                            ByteBuffer buf = (ByteBuffer) o;
                            try {
                                raw = true;
                                outputStream.write(buf.toByteArray());
                                flushIfReady();
                            } finally {
                                if (buf instanceof ReferenceCounted) {
                                    ((ReferenceCounted) buf).release();
                                }
                            }
                        } else if (codec != null) {

                            if (isJson) {
                                if (first) {
                                    outputStream.write('[');
                                    first = false;
                                } else {
                                    outputStream.write(',');
                                }
                            }
                            if (outputStream.isReady()) {
                                if (o instanceof CharSequence) {
                                    outputStream.write(o.toString().getBytes(getCharacterEncoding()));
                                } else {
                                    byte[] bytes = codec.encode(o);
                                    outputStream.write(bytes);
                                }
                                flushIfReady();
                            }
                        }

                        if (outputStream.isReady()) {
                            subscription.request(1);
                        }
                    }
                } catch (IOException e) {
                    if (finished.compareAndSet(false, true)) {
                        onError(e);
                        subscription.cancel();
                    }
                }
            }

            private void flushIfReady() throws IOException {
                if (outputStream.isReady()) {
                    outputStream.flush();
                }
            }

            @Override
            public void onError(Throwable t) {
                if (finished.compareAndSet(false, true)) {
                    emitter.error(t);
                    subscription.cancel();
                }
            }

            @Override
            public void onComplete() {
                if (finished.compareAndSet(false, true)) {
                    try {
                        if (!raw && isJson && outputStream.isReady()) {
                            if (first) { //empty publisher
                                outputStream.write(EMPTY_ARRAY);
                            } else {
                                outputStream.write(']');
                            }
                            flushIfReady();
                        }
                        emitter.next(DefaultServletHttpResponse.this);
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.error(e);
                    }
                }
            }
        }), FluxSink.OverflowStrategy.ERROR);
    }

    @Override
    @NonNull
    public Optional<MediaType> getContentType() {
        return ConversionService.SHARED.convert(delegate.getContentType(), Argument.of(MediaType.class));
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
        this.status = status;
        delegate.setStatus(status);
        return this;
    }

    @Override
    public MutableHttpResponse<B> status(int status, CharSequence message) {
        this.status = status;
        if (message == null) {
            this.reason = HttpStatus.getDefaultReason(status);
        } else {
            this.reason = message.toString();
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
    @NonNull
    public MutableHttpHeaders getHeaders() {
        return headers;
    }

    @NonNull
    @Override
    public MutableConvertibleValues<Object> getAttributes() {
        return request;
    }

    @NonNull
    @Override
    public Optional<B> getBody() {
        return Optional.ofNullable(body);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> MutableHttpResponse<T> body(@Nullable T body) {
        if (body != null) {
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
                return null;
            });
        }
        this.body = (B) body;
        return (MutableHttpResponse<T>) this;
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

    @Override
    public int code() {
        return status;
    }

    @Override
    public String reason() {
        return reason;
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
