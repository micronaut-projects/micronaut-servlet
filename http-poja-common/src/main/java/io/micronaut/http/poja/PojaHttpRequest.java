/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.http.poja;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.ConvertibleMultiValuesMap;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.ByteBody.SplitBackpressureMode;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.DirectByteBodyAccess;
import io.micronaut.http.body.stream.AvailableByteArrayBody;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.RawFormField;
import io.micronaut.servlet.http.BodyBuilder;
import io.micronaut.servlet.http.BufferedFormDecoder;
import io.micronaut.servlet.http.DefaultBodyBuilder;
import io.micronaut.servlet.http.ParsedBodyHolder;
import io.micronaut.web.router.MethodBasedRouteInfo;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.http.body.AsyncRequestBody;
import io.micronaut.core.util.SupplierUtil;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micronaut.http.uri.QueryStringDecoder;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpRequest;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;

/**
 * A base class for serverless POJA requests that provides a number of common methods
 * to be reused for body and binding.
 *
 * @param <B> The body type
 * @param <REQ> The POJA request type
 * @param <RES> The POJA response type
 * @author Andriy
 * @since 4.10.0
 */
@Internal
public abstract class PojaHttpRequest<B, REQ, RES>
        implements ServletHttpRequest<REQ, B>, ServerHttpRequest<B>, ServletExchange<REQ, RES>, MutableHttpRequest<B>,
        FormCapableHttpRequest<B>, DirectByteBodyAccess, ParsedBodyHolder<B> {

    public static final Argument<ConvertibleValues> CONVERTIBLE_VALUES_ARGUMENT = Argument.of(ConvertibleValues.class);

    protected final ConversionService conversionService;
    protected final MessageBodyHandlerRegistry messageBodyHandlerRegistry;
    protected final MutableConvertibleValues<Object> attributes = new MutableConvertibleValuesMap<>();

    /**
     * The body a filter set, which replaces the bytes of the request.
     */
    private @Nullable Object replacedBody;
    /**
     * Whether a filter set the body, even to {@code null}: the routes then no longer read the bytes of the request.
     */
    private boolean bodySet;
    private final ConcurrentLinkedQueue<Runnable> disposalResources = new ConcurrentLinkedQueue<>();
    private final BodyBuilder bodyBuilder;
    /**
     * The body the body binder decoded, which the bytes no longer hold.
     */
    private @Nullable B parsedBody;
    private final Supplier<Optional<B>> decodedBody = SupplierUtil.memoizedNonEmpty(this::decodeBody);

    private final PojaConnection connection;

    public PojaHttpRequest(
            ConversionService conversionService,
            MessageBodyHandlerRegistry messageBodyHandlerRegistry
    ) {
        this(conversionService, messageBodyHandlerRegistry, PojaConnection.UNKNOWN);
    }

    /**
     * @param conversionService The conversion service
     * @param messageBodyHandlerRegistry The message body handler registry
     * @param connection The addresses of the connection the request arrived on
     * @since 6.2.0
     */
    public PojaHttpRequest(
            ConversionService conversionService,
            MessageBodyHandlerRegistry messageBodyHandlerRegistry,
            PojaConnection connection
    ) {
        this.conversionService = conversionService;
        this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
        this.connection = connection;
        this.bodyBuilder = new DefaultBodyBuilder(messageBodyHandlerRegistry);
    }

    @Override
    public @NonNull InetSocketAddress getRemoteAddress() {
        InetSocketAddress remote = connection.remoteAddress();
        return remote != null ? remote : ServletHttpRequest.super.getRemoteAddress();
    }

    @Override
    public @NonNull InetSocketAddress getServerAddress() {
        InetSocketAddress local = connection.localAddress();
        return local != null ? local : ServletHttpRequest.super.getServerAddress();
    }

    @Override
    public abstract ByteBody byteBody();

    @Override
    public @NonNull MutableConvertibleValues<Object> getAttributes() {
        // Attributes are used for sharing internal data used by Micronaut logic.
        // We need to store them and provide when needed.
        return attributes;
    }

    /**
     * A utility method that allows consuming body.
     *
     * @return The result
     * @param <T> The function return value
     * @param consumer The method to consume the body
     */
    public <T> T consumeBody(Function<InputStream, T> consumer) {
        try (CloseableByteBody byteBody = byteBody().split(SplitBackpressureMode.FASTEST)) {
            return consumer.apply(byteBody.toInputStream());
        }
    }

    @Override
    public @NonNull Optional<B> getBody() {
        return currentBody();
    }

    @Override
    public <T> @NonNull Optional<T> getBody(@NonNull ArgumentConversionContext<T> conversionContext) {
        // not through getBody(), which an implementation may still define with this method
        return currentBody().flatMap(body -> conversionService.convert(body, conversionContext));
    }

    @SuppressWarnings("unchecked")
    private Optional<B> currentBody() {
        if (bodySet) {
            // the body the filter set, none if it cleared it
            return Optional.ofNullable((B) replacedBody);
        }
        return decodedBody.get();
    }

    @Override
    public void setParsedBody(B body) {
        this.parsedBody = body;
    }

    /**
     * The body decoded into the type of the body argument of the route, as on the servlet engine: once, because
     * decoding reads the bytes, and not at all for a route that reads the body itself through an
     * {@link AsyncRequestBody}, whose bytes it would claim.
     */
    @SuppressWarnings("unchecked")
    private Optional<B> decodeBody() {
        if (parsedBody == null && readsBodyAsynchronously()) {
            return Optional.empty();
        }
        if (parsedBody == null && isFormSubmission()) {
            // the fields of the form, read from the body
            return Optional.of((B) getFormData().asMap());
        }
        B built = parsedBody != null ? parsedBody : (B) bodyBuilder.buildBody(this::getInputStream, this);
        return Optional.ofNullable(built);
    }

    /**
     * Whether the matched route takes the body as an {@link AsyncRequestBody}, as a handler route that declares
     * a body does: it is an argument of the route method, not the body argument of the route.
     */
    private boolean readsBodyAsynchronously() {
        return RouteAttributes.getRouteInfo(this)
            .filter(MethodBasedRouteInfo.class::isInstance)
            .map(route -> {
                for (Argument<?> argument : ((MethodBasedRouteInfo<?, ?>) route).getTargetMethod().getArguments()) {
                    if (AsyncRequestBody.class.isAssignableFrom(argument.getType())) {
                        return true;
                    }
                }
                return false;
            })
            .orElse(false);
    }

    /**
     * Decides whether this request carries a body at all, without reading from it.
     *
     * <p>An absent {@code Content-Length} means the length is unknown, not that the body is empty. RFC 9112 settles
     * it: a request supplying neither {@code Content-Length} nor {@code Transfer-Encoding} has no body, so an
     * ordinary GET binds an empty body instead of failing to decode one that was never sent.</p>
     *
     * @return Whether the request carries a body
     * @since 6.2.0
     */
    protected boolean hasBody() {
        long contentLength = getContentLength();
        if (contentLength > 0) {
            return true;
        }
        if (contentLength == 0) {
            return false;
        }
        return getHeaders().contains(HttpHeaders.TRANSFER_ENCODING);
    }

    /**
     * A method used for retrieving form data. Can be overridden by specific implementations.
     *
     * @return The form data as multi-values.
     */
    protected ConvertibleMultiValues<CharSequence> getFormData() {
        return consumeBody(inputStream -> {
            try {
                String content = IOUtils.readText(new BufferedReader(new InputStreamReader(
                    inputStream, getCharacterEncoding()
                )));
                return parseFormData(content);
            } catch (IOException e) {
                throw new RuntimeException("Unable to parse body", e);
            }
        });
    }

    @Override
    public InputStream getInputStream() {
        return byteBody().split(SplitBackpressureMode.FASTEST).toInputStream();
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream()));
    }

    /**
     * Whether the request body is a form.
     *
     * @return Whether it is a form submission
     */
    public boolean isFormSubmission() {
        MediaType contentType = getContentType().orElse(null);
        return contentType != null
            && (contentType.matches(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
            || contentType.matches(MediaType.MULTIPART_FORM_DATA_TYPE));
    }

    @Override
    public @NonNull String getMethodName() {
        // a method Micronaut does not know is CUSTOM, and routes of such a method are matched by its name
        String name = getNativeMethodName();
        return name != null ? name : getMethod().name();
    }

    /**
     * @return The method as the request line spelled it, or {@code null} if unknown
     * @since 6.2.0
     */
    protected @Nullable String getNativeMethodName() {
        return null;
    }

    @Override
    public MutableHttpRequest<B> mutate() {
        // already mutable, and the changes stay in this request
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> MutableHttpRequest<T> body(@Nullable T body) {
        this.replacedBody = body;
        this.bodySet = true;
        return (MutableHttpRequest<T>) this;
    }

    /**
     * @return Whether a filter set the body, which replaces the bytes of the request
     * @since 6.2.0
     */
    protected boolean isBodySet() {
        return bodySet;
    }

    /**
     * @return The body a filter set
     * @since 6.2.0
     */
    protected @Nullable Object getReplacedBody() {
        return replacedBody;
    }

    @Override
    public @Nullable ByteBody byteBodyDirect() {
        // once the body was set, the bytes of the request are not its body
        return bodySet ? null : byteBody();
    }

    @Override
    public boolean hasFormBody() {
        return !bodySet && isFormSubmission();
    }

    @Override
    public Publisher<RawFormField> getRawFormFields() {
        if (!hasFormBody()) {
            throw new IllegalStateException("Not a form Content-Type. Please check hasFormBody() before calling this method.");
        }
        return getRawFormFields(byteBody().split(SplitBackpressureMode.FASTEST));
    }

    @Override
    public Publisher<RawFormField> getRawFormFields(ByteBody byteBody) {
        MediaType mediaType = getContentType().orElse(null);
        if (bodySet || mediaType == null || !isFormSubmission()) {
            if (byteBody instanceof CloseableByteBody closeable) {
                // the publisher owns the bytes
                closeable.close();
            }
            throw new IllegalStateException("Not a form Content-Type. Please check hasFormBody() before calling this method.");
        }
        Charset charset = getCharacterEncoding();
        ByteBodyFactory factory = byteBodyFactory();
        return Mono.fromCompletionStage(byteBody::buffer)
            .flatMapMany(buffered -> {
                byte[] bytes;
                try (buffered) {
                    bytes = buffered.toByteArray();
                }
                return Flux.fromIterable(BufferedFormDecoder.decode(mediaType, bytes, charset))
                    .map(field -> new RawFormField(field.metadata(), AvailableByteArrayBody.create(factory.readBufferFactory().adapt(field.content()))));
            })
            .doOnDiscard(RawFormField.class, RawFormField::close);
    }

    @Override
    public void addDisposalResource(Runnable dispose) {
        disposalResources.add(Objects.requireNonNull(dispose, "Disposable resource cannot be null"));
    }

    /**
     * Runs the resources added for disposal, once the request was handled.
     *
     * @since 6.2.0
     */
    protected void runDisposalResources() {
        Runnable runnable;
        while ((runnable = disposalResources.poll()) != null) {
            runnable.run();
        }
    }

    @Override
    public ServletHttpRequest<REQ, ? super Object> getRequest() {
        return (ServletHttpRequest) this;
    }

    private ConvertibleMultiValues<CharSequence> parseFormData(String body) {
        Map parameterValues = new QueryStringDecoder(body, false).parameters();

        // Remove empty values
        Iterator<Entry<String, List<CharSequence>>> iterator = parameterValues.entrySet().iterator();
        while (iterator.hasNext()) {
            List<CharSequence> value = iterator.next().getValue();
            if (value.isEmpty() || StringUtils.isEmpty(value.get(0))) {
                iterator.remove();
            }
        }

        return new ConvertibleMultiValuesMap<CharSequence>(parameterValues, conversionService);
    }

}
