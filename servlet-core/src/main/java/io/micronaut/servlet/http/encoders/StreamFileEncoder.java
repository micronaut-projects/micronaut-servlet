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
package io.micronaut.servlet.http.encoders;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.ServletHttpResponse;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;
import javax.inject.Singleton;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * A {@link io.micronaut.servlet.http.ServletResponseEncoder} for {@link StreamedFile}.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
public class StreamFileEncoder extends AbstractFileEncoder<StreamedFile> {
    @Override
    public Class<StreamedFile> getResponseType() {
        return StreamedFile.class;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> encode(
            @Nonnull ServletExchange<?, ?> exchange,
            AnnotationMetadata annotationMetadata,
            @Nonnull StreamedFile value) {
        final ServletHttpRequest<?, ? super Object> request = exchange.getRequest();
        ServletHttpResponse<?, ? super Object> response = exchange.getResponse();
        if (ifNotModified(value, request, response)) {
            return Publishers.just(
                    setDateHeader(
                            response.status(HttpStatus.NOT_MODIFIED)
                    )

            );
        }

        boolean asyncSupported = request.isAsyncSupported();
        if (asyncSupported) {
            return response.stream(Flowable.create(emitter -> {
                try (InputStream in = value.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        if (buffer.length == len) {
                            emitter.onNext(buffer);
                        } else {
                            emitter.onNext(Arrays.copyOf(buffer, len));
                        }
                    }
                    emitter.onComplete();
                } catch (Throwable e) {
                    emitter.onError(e);
                }
            }, BackpressureStrategy.BUFFER));
        } else {
            return Flowable.fromCallable(() -> {
                try (InputStream in = value.getInputStream()) {
                    try (OutputStream out = response.getOutputStream()) {
                        byte[] buffer = new byte[1024];
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
