/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.servlet.http;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Writes the pieces of a body that is still being produced, such as an event stream, with blocking writes on the
 * current thread, flushing each piece so that the client sees it then rather than when the stream ends.
 *
 * <p>The pieces are written in the order they arrived, and a failure of the body only once every piece that arrived
 * before it was written. A body read as an {@link java.io.InputStream} drops the piece it holds when the failure
 * arrives before the piece was read: e.g. the event an event stream already handed over before its handler failed
 * would never reach the client.</p>
 *
 * @since 6.2.3
 */
@Internal
final class BlockingBodyWriter implements Subscriber<byte[]> {
    private static final Object COMPLETE = new Object();

    private final BlockingQueue<Object> signals = new LinkedBlockingQueue<>();
    private final AtomicReference<@Nullable Subscription> subscription = new AtomicReference<>();

    private BlockingBodyWriter() {
    }

    /**
     * Write the pieces of the body to the stream until the body ends.
     *
     * @param body The pieces of the body
     * @param out  The stream
     * @throws IOException A failure to write, or the failure of the body as its cause, once the pieces that
     *                     arrived before it were written, like a body read as a stream reports it
     */
    static void write(Publisher<byte[]> body, OutputStream out) throws IOException {
        BlockingBodyWriter writer = new BlockingBodyWriter();
        body.subscribe(writer);
        boolean done = false;
        try {
            while (true) {
                Object signal = writer.signals.take();
                if (signal == COMPLETE) {
                    done = true;
                    return;
                }
                if (signal instanceof Failure failure) {
                    done = true;
                    throw new IOException(failure.cause());
                }
                byte[] piece = (byte[]) signal;
                out.write(piece);
                out.flush();
                writer.request();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while writing the response body", e);
        } finally {
            if (!done) {
                writer.cancel();
            }
        }
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscription.set(s);
        s.request(1);
    }

    @Override
    public void onNext(byte[] bytes) {
        signals.add(bytes);
    }

    @Override
    public void onError(Throwable t) {
        signals.add(new Failure(t));
    }

    @Override
    public void onComplete() {
        signals.add(COMPLETE);
    }

    private void request() {
        Subscription s = subscription.get();
        if (s != null) {
            s.request(1);
        }
    }

    private void cancel() {
        Subscription s = subscription.get();
        if (s != null) {
            s.cancel();
        }
    }

    private record Failure(Throwable cause) {
    }
}
