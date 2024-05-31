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
package io.micronaut.servlet.http.body;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.http.body.CloseableAvailableByteBody;
import io.micronaut.servlet.http.ByteArrayByteBuffer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

/**
 * {@link io.micronaut.http.body.AvailableByteBody} implementation based on a byte array.
 * <p>
 * Note: While internal, this is also used from the AWS and GCP modules.
 *
 * @author Jonas Konrad
 * @since 4.9.0
 */
@Internal
public final class AvailableByteArrayBody extends AbstractServletByteBody implements CloseableAvailableByteBody {
    private byte[] array;

    public AvailableByteArrayBody(byte[] array) {
        this.array = array;
    }

    @Override
    public @NonNull CloseableAvailableByteBody split() {
        if (array == null) {
            failClaim();
        }
        return new AvailableByteArrayBody(array);
    }

    @Override
    public @NonNull InputStream toInputStream() {
        return new ByteArrayInputStream(array);
    }

    @Override
    public CompletableFuture<? extends CloseableAvailableByteBody> buffer() {
        if (array == null) {
            failClaim();
        }
        CompletableFuture<AvailableByteArrayBody> f = CompletableFuture.completedFuture(new AvailableByteArrayBody(array));
        array = null;
        return f;
    }

    @Override
    public long length() {
        if (array == null) {
            failClaim();
        }
        return array.length;
    }

    @Override
    public byte @NonNull [] toByteArray() {
        byte[] a = array;
        if (a == null) {
            failClaim();
        }
        array = null;
        return a;
    }

    @Override
    public @NonNull ByteBuffer<?> toByteBuffer() {
        return new ByteArrayByteBuffer<>(toByteArray());
    }

    @Override
    public void close() {
        array = null;
    }
}
