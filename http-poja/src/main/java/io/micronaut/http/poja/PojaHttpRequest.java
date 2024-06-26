package io.micronaut.http.poja;

import io.micronaut.core.annotation.NonNull;
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
import io.micronaut.http.MediaType;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.poja.fork.netty.QueryStringDecoder;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.ServletHttpResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Iterator;
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
 * @author Andriy
 */
public abstract class PojaHttpRequest<B, REQ, RES>
        implements ServletHttpRequest<REQ, B>, ServerHttpRequest<B>, ServletExchange<REQ, RES> {

    public static final Argument<ConvertibleValues> CONVERTIBLE_VALUES_ARGUMENT = Argument.of(ConvertibleValues.class);

    protected final ConversionService conversionService;
    protected final MediaTypeCodecRegistry codecRegistry;
    protected final MutableConvertibleValues<Object> attributes = new MutableConvertibleValuesMap<>();
    protected final PojaHttpResponse<?, RES> response;

    public PojaHttpRequest(
            ConversionService conversionService,
            MediaTypeCodecRegistry codecRegistry,
            PojaHttpResponse<?, RES> response
    ) {
        this.conversionService = conversionService;
        this.codecRegistry = codecRegistry;
        this.response = response;
    }

    @Override
    public abstract CloseableByteBody byteBody();

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
     */
    public <T> T consumeBody(Function<InputStream, T> consumer) {
        try (CloseableByteBody byteBody = byteBody()) {
            InputStream stream = new LimitingInputStream(
                byteBody.toInputStream(),
                byteBody.expectedLength().orElse(0)
            );
            return consumer.apply(stream);
        }
    }

    @Override
    public <T> @NonNull Optional<T> getBody(@NonNull ArgumentConversionContext<T> conversionContext) {
        Argument<T> arg = conversionContext.getArgument();
        if (arg == null) {
            return Optional.empty();
        }
        final Class<T> type = arg.getType();
        final MediaType contentType = getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);

        if (isFormSubmission()) {
            return consumeBody(inputStream -> {
                try {
                    String content = IOUtils.readText(new BufferedReader(new InputStreamReader(
                        inputStream, getCharacterEncoding()
                    )));
                    ConvertibleMultiValues<?> form = parseFormData(content);
                    if (ConvertibleValues.class == type || Object.class == type) {
                        return Optional.of((T) form);
                    } else {
                        return conversionService.convert(form.asMap(), arg);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Unable to parse body", e);
                }
            });
        }

        final MediaTypeCodec codec = codecRegistry.findCodec(contentType, type).orElse(null);
        if (codec == null) {
            return Optional.empty();
        }
        if (ConvertibleValues.class == type || Object.class == type) {
            final Map map = consumeBody(inputStream -> codec.decode(Map.class, inputStream));
            ConvertibleValues result = ConvertibleValues.of(map);
            return Optional.of((T) result);
        } else {
            final T value = consumeBody(inputStream -> codec.decode(arg, inputStream));
            return Optional.of(value);
        }
    }

    @Override
    public InputStream getInputStream() {
        return byteBody().toInputStream();
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(byteBody().toInputStream()));
    }

    /**
     * Whether the request body is a form.
     *
     * @return Whether it is a form submission
     */
    public boolean isFormSubmission() {
        MediaType contentType = getContentType().orElse(null);
        return MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(contentType)
            || MediaType.MULTIPART_FORM_DATA_TYPE.equals(contentType);
    }

    @Override
    public ServletHttpRequest<REQ, ? super Object> getRequest() {
        return (ServletHttpRequest) this;
    }

    @Override
    public ServletHttpResponse<RES, ?> getResponse() {
        return response;
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

    /**
     * A wrapper around input stream that limits the maximum size to be read.
     */
    public static class LimitingInputStream extends InputStream {

        private long size;
        private final InputStream stream;
        private final long maxSize;

        public LimitingInputStream(InputStream stream, long maxSize) {
            this.maxSize = maxSize;
            this.stream = stream;
        }

        @Override
        public synchronized void mark(int readlimit) {
            stream.mark(readlimit);
        }

        @Override
        public int read() throws IOException {
            return stream.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            synchronized(this) {
                if (size >= maxSize) {
                    return -1;
                }
                int sizeRead = stream.read(b);
                size += sizeRead;
                return size > maxSize ? sizeRead + (int) (maxSize - size) : sizeRead;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            synchronized (this) {
                if (size >= maxSize) {
                    return -1;
                }
                int sizeRead = stream.read(b, off, len);
                size += sizeRead + off;
                return size > maxSize ? sizeRead + (int) (maxSize - size) : sizeRead;
            }
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            return stream.readAllBytes();
        }

        @Override
        public byte[] readNBytes(int len) throws IOException {
            return stream.readNBytes(len);
        }

        @Override
        public int readNBytes(byte[] b, int off, int len) throws IOException {
            return stream.readNBytes(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            return stream.skip(n);
        }

        @Override
        public void skipNBytes(long n) throws IOException {
            stream.skipNBytes(n);
        }

        @Override
        public int available() throws IOException {
            return stream.available();
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }

        @Override
        public synchronized void reset() throws IOException {
            stream.reset();
        }

        @Override
        public boolean markSupported() {
            return stream.markSupported();
        }

        @Override
        public long transferTo(OutputStream out) throws IOException {
            return stream.transferTo(out);
        }

        @Override
        public int hashCode() {
            return stream.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return stream.equals(obj);
        }

        @Override
        public String toString() {
            return stream.toString();
        }
    }

}
