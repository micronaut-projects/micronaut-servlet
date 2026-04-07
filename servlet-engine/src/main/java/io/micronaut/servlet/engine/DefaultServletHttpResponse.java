/*
 * Copyright 2017-2023 original authors
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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.io.buffer.ReferenceCounted;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseProvider;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.servlet.http.ServletHttpResponse;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ServletHttpResponse} for the Servlet API.
 * @param <B> The body type
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public final class DefaultServletHttpResponse<B> implements ServletHttpResponse<HttpServletResponse, B> {
    private static volatile boolean writeBufferAvailable = true;

    private static final Logger LOG = LoggerFactory.getLogger(DefaultServletHttpResponse.class);

    private static final byte[] EMPTY_ARRAY = "[]".getBytes();

    private final ConversionService conversionService;
    private ResponseMetadata delegate;
    private final DefaultServletHttpRequest<?> request;
    private final ServletResponseHeaders headers;
    private final MutableConvertibleValues<Object> attributes;
    private B body;
    private String reason = HttpStatus.OK.getReason();

    /**
     * Default constructor.
     *
     * @param conversionService The conversion service
     * @param request           The servlet request
     * @param delegate          The servlet response
     */
    DefaultServletHttpResponse(ConversionService conversionService,
                               DefaultServletHttpRequest<B> request,
                               HttpServletResponse delegate) {
        this(conversionService, request, delegate, new ServletResponseHeaders(delegate, conversionService));
    }

    /**
     * Default constructor.
     *
     * @param conversionService The conversion service
     * @param request           The servlet request
     * @param delegate          The servlet response
     */
    DefaultServletHttpResponse(ConversionService conversionService,
                               DefaultServletHttpRequest<B> request,
                               HttpServletResponse delegate,
                               ServletResponseHeaders headers) {
        this.conversionService = conversionService;
        this.delegate = new DelegateResponseMetadata(delegate);
        this.attributes = new MutableConvertibleValuesMap<>(new LinkedHashMap<>(), conversionService);
        this.request = request;
        this.headers = headers;
    }

    DefaultServletHttpResponse<?> createNewPrimaryResponse() {
        HttpServletResponse nativeResponse = ((DelegateResponseMetadata) delegate).delegate;
        DefaultServletHttpResponse<?> newPrimary = new DefaultServletHttpResponse<>(conversionService, request, nativeResponse, headers);
        delegate = new LocalResponseMetadata();
        nativeResponse.reset();
        return newPrimary;
    }

    @Override
    public boolean isCommitted() {
        return delegate.isCommitted();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> stream(Publisher<?> dataPublisher) {
        return Flux.create(emitter -> dataPublisher.subscribe(new Subscriber<Object>() {
            ServletOutputStream outputStream;
            Subscription subscription;
            final AtomicBoolean finished = new AtomicBoolean();
            MediaType contentType = getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
            boolean isJson = "json".equalsIgnoreCase(contentType.getSubtype());
            boolean first = true;
            boolean raw = false;
            boolean written = false;
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

                        writeToOutputStream(o);

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

            private void writeToOutputStream(Object o) throws IOException {
                written = true;
                if (o instanceof byte[] byteArray) {
                    raw = true;
                    outputStream.write(byteArray);
                    flushIfReady();
                    return;
                }
                if (o instanceof ByteBuffer buf) {
                    try {
                        raw = true;
                        outputStream.write(buf.toByteArray());
                        flushIfReady();
                    } finally {
                        if (buf instanceof ReferenceCounted referenceCounted) {
                            referenceCounted.release();
                        }
                    }
                    return;
                }

                if (!raw && isJson) {
                    if (first) {
                        outputStream.write('[');
                        first = false;
                    } else {
                        outputStream.write(',');
                    }
                }

                if (!outputStream.isReady()) {
                    return;
                }

                if (o instanceof CharSequence charSequence) {
                    outputStream.write(charSequence.toString().getBytes(getCharacterEncoding()));
                } else {
                    @SuppressWarnings("unchecked")
                    Argument<Object> argument = (Argument<Object>) Argument.of(o.getClass());
                    byte[] encoded = encodeBody(argument, o, contentType);
                    outputStream.write(encoded);
                }
                flushIfReady();
            }

            private byte[] encodeBody(Argument<Object> argument, Object value, MediaType mediaType) throws IOException {
                MessageBodyWriter<Object> writer = null;
                MessageBodyHandlerRegistry registry = request.getMessageBodyHandlerRegistry();
                if (registry != null) {
                    writer = registry.findWriter(argument, mediaType).orElse(null);
                    if (writer == null) {
                        writer = registry.findWriter(argument).orElse(null);
                    }
                }
                if (writer != null) {
                    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        writer.writeTo(argument, mediaType, value, getHeaders(), baos);
                        return baos.toByteArray();
                    }
                }
                return conversionService.convert(value, byte[].class)
                    .orElseGet(() -> value.toString().getBytes(getCharacterEncoding()));
            }

            private void flushIfReady() throws IOException {
                if (outputStream.isReady()) {
                    outputStream.flush();
                }
            }

            @Override
            public void onError(Throwable t) {
                if (finished.compareAndSet(false, true)) {
                    if (t instanceof HttpStatusException) {
                        maybeReportErrorDownstream(t);
                    } else {
                        if (LOG.isWarnEnabled()) {
                            LOG.warn("Reactive response received an error after some data has already been written. This error cannot be forwarded to the client.", t);
                        }
                        maybeReportErrorDownstream(new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.getReason() + ": " + t.getMessage()));
                    }
                    subscription.cancel();
                }
            }

            private void maybeReportErrorDownstream(Throwable t) {
                HttpStatusException httpStatusException = (HttpStatusException) t;
                delegate.setStatus(httpStatusException.getStatus().getCode());
                if (!written) {
                    try {
                        Object message = httpStatusException.getBody().orElse(httpStatusException.getMessage());
                        if (outputStream.isReady() && message instanceof CharSequence) {
                            outputStream.write(message.toString().getBytes(getCharacterEncoding()));
                            flushIfReady();
                        } else if (outputStream.isReady()) {
                            writeToOutputStream(message);
                        }
                        finish();
                    } catch (IOException e) {
                        emitter.error(e);
                    }
                } else {
                    // Nothing we can do really...
                    emitter.error(t);
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
                        finish();
                    } catch (IOException e) {
                        emitter.error(e);
                    }
                }
            }

            private void finish() {
                emitter.next(DefaultServletHttpResponse.this);
                emitter.complete();
            }
        }), FluxSink.OverflowStrategy.ERROR);
    }

    @Override
    public CompletableFuture<?> stream(CloseableByteBody body) {
        CompletableFuture<?> completion = new CompletableFuture<>();
        Subscriber<byte[]> subscriber = null;
        try {
            body.expectedLength().ifPresent(delegate::setContentLengthLong);
            subscriber = new Subscriber<>() {
                final ServletOutputStream outputStream = delegate.getOutputStream();
                final AtomicReference<CloseState> closeState = new AtomicReference<>(CloseState.IDLE);
                Throwable failure;
                Subscription subscription;
                java.nio.ByteBuffer internalBuffer;

                @Override
                public void onSubscribe(Subscription s) {
                    this.subscription = s;
                    outputStream.setWriteListener(new WriteListener() {
                        @Override
                        public void onWritePossible() throws IOException {
                            if (internalBuffer == null) {
                                s.request(1);
                            } else {
                                writeSome();
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            completion.completeExceptionally(t);
                        }
                    });
                }

                private void writeSome() throws IOException {
                    assert internalBuffer != null;

                    // isReady at the start, ensured by caller. we can't assert this here because
                    // isReady may have side effects

                    while (internalBuffer.hasRemaining()) { // hasRemaining is only legal when isReady!

                        boolean writeBuffer = writeBufferAvailable;
                        if (writeBuffer) {
                            try {
                                outputStream.write(internalBuffer);
                            } catch (NoSuchMethodError e) {
                                writeBuffer = false;
                                writeBufferAvailable = false;
                            }
                        }
                        if (!writeBuffer) {
                            outputStream.write(internalBuffer.array(), internalBuffer.arrayOffset() + internalBuffer.position(), internalBuffer.remaining());
                            internalBuffer.position(internalBuffer.limit());
                        }

                        if (!outputStream.isReady()) {
                            // wait for onWritePossible
                            return;
                        }
                    }

                    internalBuffer = null;
                    if (closeState.getAndSet(CloseState.IDLE) == CloseState.INPUT_CLOSED) {
                        if (failure == null) {
                            completion.complete(null);
                        } else {
                            completion.completeExceptionally(failure);
                        }
                    } else {
                        subscription.request(1);
                    }
                }

                @Override
                public void onNext(byte[] bytes) {
                    if (internalBuffer != null) {
                        throw new IllegalStateException("Still have buffered data");
                    }
                    internalBuffer = java.nio.ByteBuffer.wrap(bytes);
                    closeState.set(CloseState.UNPROCESSED_DATA);
                    try {
                        writeSome();
                    } catch (IOException e) {
                        completion.completeExceptionally(e);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    failure = t;
                    if (closeState.getAndSet(CloseState.INPUT_CLOSED) == CloseState.IDLE) {
                        completion.completeExceptionally(t);
                    }
                }

                @Override
                public void onComplete() {
                    if (closeState.getAndSet(CloseState.INPUT_CLOSED) == CloseState.IDLE) {
                        completion.complete(null);
                    }
                }

                enum CloseState {
                    /**
                     * We're waiting for an onNext or onComplete call. If onComplete is called in
                     * this state, we can complete the future immediately.
                     */
                    IDLE,
                    /**
                     * We have some unprocessed data from an onNext call. If onComplete is called
                     * in this state, we transition to INPUT_CLOSED but don't complete the future
                     * yet.
                     */
                    UNPROCESSED_DATA,
                    /**
                     * We've received an onComplete call. If the processor sees this when trying
                     * to switch back to IDLE, it completes the future.
                     */
                    INPUT_CLOSED,
                }
            };
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            if (subscriber == null) {
                body.close();
            }
        }
        body.toByteArrayPublisher().subscribe(subscriber);
        return completion;
    }

    @Override
    @NonNull
    public Optional<MediaType> getContentType() {
        return conversionService.convert(delegate.getContentType(), Argument.of(MediaType.class));
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
    public MutableHttpResponse<B> status(HttpStatus status) {
        return status(Objects.requireNonNull(status, "status cannot be null").getCode());
    }

    @Override
    public HttpServletResponse getNativeResponse() {
        return delegate.getNativeResponse();
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
        if (cookie instanceof ServletCookieAdapter servletCookieAdapter) {
            delegate.addCookie(
                servletCookieAdapter.getCookie()
            );
        } else {

            final jakarta.servlet.http.Cookie c = new jakarta.servlet.http.Cookie(
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
        return attributes;
    }

    @NonNull
    @Override
    public Optional<B> getBody() {
        return Optional.ofNullable(body);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> MutableHttpResponse<T> body(@Nullable T body) {
        if (body instanceof HttpResponseProvider responseProvider) {
            HttpResponse<T> response = (HttpResponse<T>) responseProvider.getResponse();
            if (response != this && response.body() != null) {
                body(response.body());
            }
        } else {
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
        }
        return (MutableHttpResponse<T>) this;
    }

    @Override
    public MutableHttpResponse<B> status(int status, CharSequence message) {
        if (message == null) {
            this.reason = HttpStatus.getDefaultReason(status);
        } else {
            this.reason = message.toString();
        }
        if (!delegate.isCommitted()) {
            delegate.setStatus(status);
        }
        return this;
    }

    @Override
    public int code() {
        return delegate.getStatus();
    }

    @Override
    public String reason() {
        if (reason != null) {
            return reason;
        }
        try {
            return HttpStatus.valueOf(delegate.getStatus()).getReason();
        } catch (Exception e) {
            return "";
        }
    }

    private sealed interface ResponseMetadata {
        HttpServletResponse getNativeResponse();

        ServletOutputStream getOutputStream() throws IOException;

        Writer getWriter() throws IOException;

        Collection<String> getHeaders(String k);

        Collection<String> getHeaderNames();

        @Nullable String getHeader(String k);

        void setHeader(String k, String v);

        void addHeader(String k, String v);

        boolean containsHeader(String k);

        boolean isCommitted();

        int getStatus();

        void setStatus(int code);

        void setContentLengthLong(long l);

        String getContentType();

        void setContentType(String contentType);

        void setLocale(Locale locale);

        void addCookie(jakarta.servlet.http.Cookie cookie);
    }

    private record DelegateResponseMetadata(HttpServletResponse delegate) implements ResponseMetadata {
        @Override
        public HttpServletResponse getNativeResponse() {
            return delegate;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            return delegate.getOutputStream();
        }

        @Override
        public Writer getWriter() throws IOException {
            return delegate.getWriter();
        }

        @Override
        public boolean containsHeader(String k) {
            return delegate.containsHeader(k);
        }

        @Override
        public String getHeader(String k) {
            return delegate.getHeader(k);
        }

        @Override
        public Collection<String> getHeaderNames() {
            return delegate.getHeaderNames();
        }

        @Override
        public Collection<String> getHeaders(String k) {
            return delegate.getHeaders(k);
        }

        @Override
        public void setHeader(String k, String v) {
            delegate.setHeader(k, v);
        }

        @Override
        public void addHeader(String k, String v) {
            delegate.addHeader(k, v);
        }

        @Override
        public boolean isCommitted() {
            return delegate.isCommitted();
        }

        @Override
        public int getStatus() {
            return delegate.getStatus();
        }

        @Override
        public void setStatus(int code) {
            delegate.setStatus(code);
        }

        @Override
        public void setContentLengthLong(long l) {
            delegate.setContentLengthLong(l);
        }

        @Override
        public String getContentType() {
            return delegate.getContentType();
        }

        @Override
        public void setContentType(String contentType) {
            delegate.setContentType(contentType);
        }

        @Override
        public void setLocale(Locale locale) {
            delegate.setLocale(locale);
        }

        @Override
        public void addCookie(jakarta.servlet.http.Cookie cookie) {
            delegate.addCookie(cookie);
        }
    }

    private final class LocalResponseMetadata implements ResponseMetadata {
        private int code;
        private final MutableHttpHeaders headers;

        LocalResponseMetadata() {
            headers = new SimpleHttpHeaders(conversionService);
            for (String k : getHeaderNames()) {
                for (String v : getHeaders(k)) {
                    headers.add(k, v);
                }
            }
            code = delegate.getStatus();
        }

        private static IllegalStateException unsupported() {
            return new IllegalStateException("Another response was created for this request");
        }

        @Override
        public HttpServletResponse getNativeResponse() {
            throw unsupported();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            throw unsupported();
        }

        @Override
        public Writer getWriter() throws IOException {
            throw unsupported();
        }

        @Override
        public Collection<String> getHeaders(String k) {
            return headers.getAll(k);
        }

        @Override
        public Collection<String> getHeaderNames() {
            return headers.names();
        }

        @Override
        public String getHeader(String k) {
            return headers.get(k);
        }

        @Override
        public void setHeader(String k, String v) {
            headers.set(k, v);
        }

        @Override
        public void addHeader(String k, String v) {
            headers.add(k, v);
        }

        @Override
        public boolean containsHeader(String k) {
            return headers.contains(k);
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public int getStatus() {
            return code;
        }

        @Override
        public void setStatus(int code) {
            this.code = code;
        }

        @Override
        public void setContentLengthLong(long l) {
            setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(l));
        }

        @Override
        public String getContentType() {
            return headers.getContentType().orElse(null);
        }

        @Override
        public void setContentType(String contentType) {
            setHeader(HttpHeaders.CONTENT_TYPE, contentType);
        }

        @Override
        public void setLocale(Locale locale) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addCookie(jakarta.servlet.http.Cookie cookie) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * The response headers.
     */
    private static final class ServletResponseHeaders implements MutableHttpHeaders {

        private final HttpServletResponse delegate;
        private final ConversionService conversionService;

        private ServletResponseHeaders(HttpServletResponse delegate, ConversionService conversionService) {
            this.delegate = delegate;
            this.conversionService = conversionService;
        }

        private static boolean isBanned(String name) {
            // transfer-encoding cannot be cleared on tomcat, so we must never set it
            return name.equalsIgnoreCase(HttpHeaders.TRANSFER_ENCODING) ||
                name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH);
        }

        @Override
        public MutableHeaders set(CharSequence header, CharSequence value) {
            final String headerName =
                Objects.requireNonNull(header, "Header name cannot be null").toString();

            final String headerValue =
                Objects.requireNonNull(value, "Header value cannot be null").toString();

            if (isBanned(headerName)) {
                return this;
            }

            delegate.setHeader(
                headerName,
                headerValue
            );
            return this;
        }

        @Override
        public MutableHttpHeaders add(CharSequence header, CharSequence value) {
            final String headerName =
                    Objects.requireNonNull(header, "Header name cannot be null").toString();

            final String headerValue =
                    Objects.requireNonNull(value, "Header value cannot be null").toString();

            if (isBanned(headerName)) {
                return this;
            }

            delegate.addHeader(
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
                return conversionService.convert(v, conversionContext);
            }
            return Optional.empty();
        }

        @Override
        public void setConversionService(ConversionService conversionService) {
        }
    }
}
