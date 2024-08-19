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
