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


import io.micronaut.core.io.file.TemporaryFileResource;
import io.micronaut.http.MediaType;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.FormFieldMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Utility to convert a servlet {@link jakarta.servlet.http.Part} into a Micronaut {@link CompletedFileUpload}.
 */
final class ServletCompletedFileUploadFactory {

    private static final String TEMP_FILE_PREFIX = "micronaut-servlet-upload-";

    private ServletCompletedFileUploadFactory() {
    }

    static CompletedFileUpload create(jakarta.servlet.http.Part part) throws IOException {
        FormFieldMetadata metadata = new FormFieldMetadata(
            part.getName(),
            part.getSubmittedFileName(),
            Optional.ofNullable(part.getContentType()).map(MediaType::new).orElse(null)
        );

        Path tempFile = Files.createTempFile(TEMP_FILE_PREFIX, ".tmp");
        TemporaryFileResource resource = new TemporaryFileResource(tempFile);
        try (InputStream inputStream = part.getInputStream()) {
            long copiedBytes = Files.copy(inputStream, resource.getPath(), StandardCopyOption.REPLACE_EXISTING);
            long reportedSize = part.getSize();
            long actualSize = reportedSize >= 0 ? reportedSize : copiedBytes;
            return CompletedFileUpload.ofFile(metadata, resource, actualSize);
        } catch (RuntimeException | IOException e) {
            try {
                resource.close();
            } catch (IOException closeException) {
                e.addSuppressed(closeException);
            }
            throw e;
        }
    }
}
