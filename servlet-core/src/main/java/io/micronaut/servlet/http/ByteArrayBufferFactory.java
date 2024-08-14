package io.micronaut.servlet.http;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.io.buffer.ByteBufferFactory;

/**
 * {@link ByteBufferFactory} implementation based on simple byte arrays.
 *
 * @since 4.10.0
 * @author Jonas Konrad
 */
@Internal
public class ByteArrayBufferFactory implements ByteBufferFactory<Void, byte[]> {
    public static final ByteArrayBufferFactory INSTANCE = new ByteArrayBufferFactory();

    private ByteArrayBufferFactory() {
    }

    @Override
    public Void getNativeAllocator() {
        throw new UnsupportedOperationException("No native allocator");
    }

    @Override
    public ByteBuffer<byte[]> buffer() {
        return buffer(0);
    }

    @Override
    public ByteBuffer<byte[]> buffer(int initialCapacity) {
        return new ByteArrayByteBuffer<>(new byte[initialCapacity]);
    }

    @Override
    public ByteBuffer<byte[]> buffer(int initialCapacity, int maxCapacity) {
        return buffer(initialCapacity);
    }

    @Override
    public ByteBuffer<byte[]> copiedBuffer(byte[] bytes) {
        return wrap(bytes.clone());
    }

    @Override
    public ByteBuffer<byte[]> copiedBuffer(java.nio.ByteBuffer nioBuffer) {
        int pos = nioBuffer.position();
        int lim = nioBuffer.limit();
        byte[] arr = new byte[lim - pos];
        nioBuffer.get(pos, arr, 0, arr.length);
        return wrap(arr);
    }

    @Override
    public ByteBuffer<byte[]> wrap(byte[] existing) {
        return new ByteArrayByteBuffer<>(existing);
    }
}
