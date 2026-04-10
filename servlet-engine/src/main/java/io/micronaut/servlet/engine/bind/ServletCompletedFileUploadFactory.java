/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.servlet.engine.bind;


import io.micronaut.core.io.buffer.ReadBufferFactory;
import io.micronaut.core.io.file.TemporaryFileResource;
import io.micronaut.http.MediaType;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.FormFieldMetadata;
import io.micronaut.http.server.HttpServerConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Utility to convert a servlet {@link jakarta.servlet.http.Part} into a Micronaut {@link CompletedFileUpload}.
 */
final class ServletCompletedFileUploadFactory {

    private ServletCompletedFileUploadFactory() {
    }

    static CompletedFileUpload create(HttpServerConfiguration configuration, jakarta.servlet.http.Part part) throws IOException {
        FormFieldMetadata metadata = new FormFieldMetadata(
            part.getName(),
            part.getSubmittedFileName(),
            Optional.ofNullable(part.getContentType()).map(MediaType::new).orElse(null)
        );

        boolean writeToDisk;
        long size;
        if (configuration.getMultipart().isMixed()) {
            size = part.getSize();
            writeToDisk = size >= configuration.getMultipart().getThreshold();
        } else {
            size = -1;
            writeToDisk = configuration.getMultipart().isDisk();
        }

        if (writeToDisk) {
            if (size == -1) {
                size = part.getSize();
            }

            Path tmpFile;
            if (configuration.getMultipart().getLocation().isPresent()) {
                tmpFile = Files.createTempFile(configuration.getMultipart().getLocation().get().toPath(), "FUp_", ".tmp");
            } else {
                tmpFile = Files.createTempFile("FUp_", ".tmp");
            }
            try (TemporaryFileResource tmpFileResource = new TemporaryFileResource(tmpFile)) {
                part.write(tmpFile.toAbsolutePath().toString());
                return CompletedFileUpload.ofFile(metadata, tmpFileResource.moveResource(), size);
            }
        } else {
            try (InputStream is = part.getInputStream()) {
                return CompletedFileUpload.ofMemory(metadata, ReadBufferFactory.getJdkFactory().adapt(is.readAllBytes()));
            }
        }
    }
}
