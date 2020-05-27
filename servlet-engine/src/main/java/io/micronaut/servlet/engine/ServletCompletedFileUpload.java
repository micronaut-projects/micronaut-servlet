/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.engine;

import io.micronaut.http.MediaType;
import io.micronaut.http.multipart.CompletedFileUpload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

/**
 * An implementation of {@link CompletedFileUpload} that backs on a Servlet part.
 *
 * @author graemerocher
 * @since 1.0.0
 */
public final class ServletCompletedFileUpload implements CompletedFileUpload {
    private final javax.servlet.http.Part part;

    /**
     * Default constructor.
     * @param part The part
     */
    public ServletCompletedFileUpload(javax.servlet.http.Part part) {
        this.part = Objects.requireNonNull(part, "Part cannot be null");
    }

    @Override
    public Optional<MediaType> getContentType() {
        return Optional.ofNullable(part.getContentType())
                .map(MediaType::new);
    }

    @Override
    public String getName() {
        return part.getName();
    }

    @Override
    public String getFilename() {
        return part.getSubmittedFileName();
    }

    @Override
    public long getSize() {
        return part.getSize();
    }

    @Override
    public long getDefinedSize() {
        return part.getSize();
    }

    @Override
    public boolean isComplete() {
        return true;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return part.getInputStream();
    }

    @Override
    public byte[] getBytes() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[16384];
        InputStream inputStream = getInputStream();
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        return buffer.toByteArray();
    }

    @Override
    public ByteBuffer getByteBuffer() throws IOException {
        return ByteBuffer.wrap(getBytes());
    }
}
