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
package io.micronaut.servlet.engine.body;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.http.body.CloseableAvailableByteBody;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.servlet.http.ByteArrayByteBuffer;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Streaming {@link io.micronaut.http.body.ByteBody} implementation for servlet.
 *
 * @since 4.9.0
 * @author Jonas Konrad
 */
@Internal
public final class InputStreamByteBody extends AbstractServletByteBody {
    private final Context context;
    private ExtendedInputStream stream;

    private InputStreamByteBody(Context context, ExtendedInputStream stream) {
        this.context = context;
        this.stream = stream;
    }

    public static InputStreamByteBody create(InputStream stream, OptionalLong length, Executor ioExecutor) {
        return create(ExtendedInputStream.wrap(stream), length, ioExecutor);
    }

    static InputStreamByteBody create(ExtendedInputStream stream, OptionalLong length, Executor ioExecutor) {
        return new InputStreamByteBody(new Context(length, ioExecutor), stream);
    }

    @Override
    public @NonNull CloseableByteBody allowDiscard() {
        stream.allowDiscard();
        return this;
    }

    @Override
    public void close() {
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }

    @Override
    public @NonNull CloseableByteBody split(SplitBackpressureMode backpressureMode) {
        if (stream == null) {
            failClaim();
        }
        StreamPair.Pair pair = StreamPair.createStreamPair(stream, backpressureMode);
        stream = pair.left();
        return new InputStreamByteBody(context, pair.right());
    }

    @Override
    public @NonNull OptionalLong expectedLength() {
        return context.expectedLength();
    }

    @Override
    public @NonNull ExtendedInputStream toInputStream() {
        ExtendedInputStream s = stream;
        if (s == null) {
            failClaim();
        }
        stream = null;
        return s;
    }

    @Override
    public @NonNull Flux<byte[]> toByteArrayPublisher() {
        ExtendedInputStream s = toInputStream();
        Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();
        return sink.asFlux()
            .doOnRequest(req -> {
                long remaining = req;
                while (remaining > 0) {
                    @Nullable byte[] arr;
                    try {
                        arr = s.readSome();
                    } catch (IOException e) {
                        sink.tryEmitError(e);
                        break;
                    }
                    if (arr == null) {
                        sink.tryEmitComplete();
                        break;
                    } else {
                        remaining--;
                        sink.tryEmitNext(arr);
                    }
                }
            })
            .doOnTerminate(s::close)
            .doOnCancel(s::close)
            .subscribeOn(Schedulers.fromExecutor(context.ioExecutor()));
    }

    @Override
    public @NonNull Publisher<ByteBuffer<?>> toByteBufferPublisher() {
        return toByteArrayPublisher().map(ByteArrayByteBuffer::new);
    }

    @Override
    public CompletableFuture<? extends CloseableAvailableByteBody> buffer() {
        ExtendedInputStream s = toInputStream();
        return CompletableFuture.supplyAsync(() -> {
            try (ExtendedInputStream t = s) {
                return new AvailableByteArrayBody(t.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, context.ioExecutor);
    }

    private record Context(
        OptionalLong expectedLength,
        Executor ioExecutor
    ) {
    }
}
