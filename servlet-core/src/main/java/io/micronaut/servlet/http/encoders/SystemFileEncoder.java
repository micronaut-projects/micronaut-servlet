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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.types.files.SystemFile;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.ServletHttpResponse;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * A {@link io.micronaut.servlet.http.ServletResponseEncoder} for {@link SystemFile}.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
public class SystemFileEncoder extends AbstractFileEncoder<SystemFile> {
    private static final int BUFFER_SIZE = 1024;

    @Override
    public Class<SystemFile> getResponseType() {
        return SystemFile.class;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> encode(
            @NonNull ServletExchange<?, ?> exchange,
            AnnotationMetadata annotationMetadata,
            @NonNull SystemFile value) {
        final ServletHttpRequest<?, ? super Object> request = exchange.getRequest();
        ServletHttpResponse<?, ?> response = exchange.getResponse();
        if (ifNotModified(value, request, response)) {
            return Publishers.just(
                    setDateHeader(
                            response.status(HttpStatus.NOT_MODIFIED)
                    )
            );
        }

        if (!value.getFile().exists()) {
            return Publishers.just(
                    response.status(HttpStatus.NOT_FOUND)
            );
        }

        boolean asyncSupported = request.isAsyncSupported();
        if (asyncSupported) {
            return response.stream(Flux.create(emitter -> {
                try (InputStream in = new FileInputStream(value.getFile())) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        if (buffer.length == len) {
                            emitter.next(buffer);
                            buffer = new byte[BUFFER_SIZE];
                        } else {
                            emitter.next(Arrays.copyOf(buffer, len));
                        }
                    }
                    emitter.complete();
                } catch (Throwable e) {
                    emitter.error(e);
                }
            }, FluxSink.OverflowStrategy.BUFFER));
        } else {
            return Mono.fromCallable(() -> {
                try (InputStream in = new FileInputStream(value.getFile())) {
                    try (OutputStream out = response.getOutputStream()) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
                return response;
            });
        }
    }
}
