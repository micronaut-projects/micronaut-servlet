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
package io.micronaut.servlet.engine;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.exceptions.ContentLengthExceededException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Enforces {@code micronaut.server.max-request-size} on a request body that is read as a stream, so that a
 * body without a declared length (chunked) or one that exceeds its declared length cannot grow past the limit.
 * Reading past the limit fails with {@link ContentLengthExceededException}, which the shared error handling
 * turns into {@code 413 Request Entity Too Large} as it does on the Netty server.
 *
 * @since 6.2.0
 */
@Internal
final class LimitedInputStream extends FilterInputStream {
    private final long maxBodySize;
    private long consumed;

    LimitedInputStream(InputStream delegate, long maxBodySize) {
        super(delegate);
        this.maxBodySize = maxBodySize;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) {
            consumed(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0) {
            consumed(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = in.skip(n);
        if (skipped > 0) {
            consumed(skipped);
        }
        return skipped;
    }

    private void consumed(long n) {
        consumed += n;
        if (consumed > maxBodySize) {
            throw new ContentLengthExceededException(maxBodySize, consumed);
        }
    }
}
