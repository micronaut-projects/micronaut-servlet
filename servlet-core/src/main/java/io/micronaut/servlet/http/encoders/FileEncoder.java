/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.servlet.http.encoders;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.types.files.SystemFile;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletResponseEncoder;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;
import javax.inject.Singleton;
import java.io.File;

/**
 * Handles {@link File}.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
public class FileEncoder implements ServletResponseEncoder<File> {
    @Override
    public Class<File> getResponseType() {
        return File.class;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> encode(@Nonnull ServletExchange<?, ?> exchange, AnnotationMetadata annotationMetadata, @Nonnull File value) {
        return new SystemFileEncoder().encode(exchange, annotationMetadata, new SystemFile(value));
    }
}
