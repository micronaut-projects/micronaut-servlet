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
package io.micronaut.http.poja.util;

import java.io.IOException;
import java.io.InputStream;

/**
 * A wrapper around input stream that limits the maximum size to be read.
 */
public class LimitingInputStream extends InputStream {

    private long size;
    private final InputStream stream;
    private final long maxSize;

    /**
     * Create the limiting input stream.
     *
     * @param stream The delegate stream
     * @param maxSize The maximum size to read
     */
    public LimitingInputStream(InputStream stream, long maxSize) {
        this.maxSize = maxSize;
        this.stream = stream;
    }

    @Override
    public int read() throws IOException {
        if (size >= maxSize) {
            return -1;
        }
        ++size;
        return stream.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        synchronized (this) {
            if (size >= maxSize) {
                return -1;
            }
            if (b.length + size > maxSize) {
                return read(b, 0, (int) (maxSize - size));
            }
            int sizeRead = stream.read(b);
            size += sizeRead;
            return sizeRead;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        synchronized (this) {
            if (size >= maxSize) {
                return -1;
            }
            int lengthToRead = (int) Math.min(len, maxSize - size);
            int sizeRead = stream.read(b, off, lengthToRead);
            size += sizeRead;
            return sizeRead;
        }
    }

    @Override
    public int available() throws IOException {
        return (int) (maxSize - size);
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }

}
