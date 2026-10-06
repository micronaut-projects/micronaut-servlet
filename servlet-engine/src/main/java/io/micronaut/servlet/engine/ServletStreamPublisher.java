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

import io.micronaut.core.util.functional.ThrowingSupplier;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link ServletInputStream} as a {@link Publisher}.
 *
 * @since 5.1.0
 * @author Jonas Konrad
 */
final class ServletStreamPublisher implements Publisher<ByteBuffer>, Subscription, ReadListener {
    private static final Logger LOG = LoggerFactory.getLogger(ServletStreamPublisher.class);

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicReference<WorkState> state = new AtomicReference<>(WorkState.IDLE);

    private final ThrowingSupplier<ServletInputStream, IOException> upstreamSupplier;

    private @Nullable ServletInputStream upstream;
    private long demand;
    private boolean upstreamListenerRegistered;
    private boolean upstreamReady;
    private boolean upstreamDone;
    private boolean downstreamDone;
    private @Nullable Throwable error;
    private @Nullable Subscriber<? super ByteBuffer> downstream;
    /**
     * Runs once the rest of the body was dropped, while it is being dropped, see {@link #discard}.
     */
    private @Nullable Runnable discarded;
    private final long maxDiscarded;
    private long discardLimit = Long.MAX_VALUE;
    private long discardedBytes;
    private final byte[] discardBuffer = new byte[4096];

    /**
     * @param upstreamSupplier The stream of the request
     * @param maxDiscarded     The most bytes of a body nobody reads that are dropped before the connection is
     *                         dropped instead, the size limit of the server
     */
    ServletStreamPublisher(ThrowingSupplier<ServletInputStream, IOException> upstreamSupplier, long maxDiscarded) {
        this.upstreamSupplier = upstreamSupplier;
        this.maxDiscarded = maxDiscarded;
    }

    /**
     * Concurrency control mechanism. This task queue avoids both concurrency (tasks may not run at
     * the same time on different threads) and reentrancy (a submit call inside a task will have
     * the second task run after the first task, not immediately).
     *
     * @param r A task to run now or in the future
     */
    private void submit(Runnable r) {
        tasks.add(r);
        WorkState oldState = state.getAndUpdate(w -> w == WorkState.IDLE ? WorkState.WORKING : WorkState.WORKING_PENDING_TASKS);
        if (oldState != WorkState.IDLE) {
            return;
        }

        // it's our job to do some work.
        while (true) {
            while (true) {
                Runnable task = tasks.poll();
                if (task == null) {
                    break;
                }
                task.run();
            }

            if (state.updateAndGet(w -> w == WorkState.WORKING ? WorkState.IDLE : WorkState.WORKING) == WorkState.IDLE) {
                // no more tasks
                break;
            }
        }
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> s) {
        downstream = s;
        submit(() -> s.onSubscribe(this));
    }

    @Override
    public void request(long n) {
        submit(() -> {
            long oldDemand = this.demand;
            this.demand = oldDemand + n < oldDemand ? Long.MAX_VALUE : oldDemand + n;
            if (!upstreamListenerRegistered) {
                upstreamListenerRegistered = true;
                try {
                    upstream = upstreamSupplier.get();
                    upstream().setReadListener(this);
                } catch (IOException e) {
                    onError(e);
                }
            } else {
                forwardSome();
            }
        });
    }

    private void forwardSome() {
        Subscriber<? super ByteBuffer> currentDownstream = downstream();
        while (!downstreamDone && demand > 0 && (upstreamReady || upstreamDone)) {
            if (!upstreamDone) {
                try {
                    byte[] arr = new byte[4096];
                    ServletInputStream currentUpstream = upstream();
                    int n = currentUpstream.read(arr);
                    if (n == -1) {
                        upstreamDone = true;
                    } else {
                        demand--;
                        currentDownstream.onNext(ByteBuffer.wrap(arr, 0, n));
                        upstreamReady = currentUpstream.isReady();
                    }
                } catch (IOException e) {
                    error = e;
                    upstreamDone = true;
                }
            }
            if (upstreamDone) {
                downstreamDone = true;
                if (error != null) {
                    currentDownstream.onError(error);
                } else {
                    currentDownstream.onComplete();
                }
            }
        }
    }

    private ServletInputStream upstream() {
        return Objects.requireNonNull(upstream, "Upstream not initialized");
    }

    private Subscriber<? super ByteBuffer> downstream() {
        return Objects.requireNonNull(downstream, "Downstream not initialized");
    }

    @Override
    public void cancel() {
        // the rest of the body is read and dropped, like the Netty server does: closing the stream would have the
        // container drop the connection while the client is still sending, which it sees as a broken pipe
        discard(maxDiscarded, () -> { });
    }

    /**
     * Reads the rest of the body and drops it, e.g. the body the route did not read, then runs the callback,
     * once: the response of the request can then complete without the connection being dropped.
     *
     * @param limit The most bytes to drop: past it the callback runs, and the container drops the connection
     * @param done  Runs once the body has been read, failed or passed the limit
     */
    void discard(long limit, Runnable done) {
        submit(() -> {
            downstreamDone = true;
            Runnable previous = discarded;
            discarded = previous == null ? done : () -> {
                previous.run();
                done.run();
            };
            discardLimit = Math.min(discardLimit, limit);
            if (!upstreamListenerRegistered) {
                upstreamListenerRegistered = true;
                try {
                    ServletInputStream stream = upstreamSupplier.get();
                    upstream = stream;
                    if (stream.isFinished()) {
                        // e.g. the container parsed a form from it: there is nothing left to drop
                        upstreamDone = true;
                    } else {
                        stream.setReadListener(this);
                    }
                } catch (IOException | IllegalStateException e) {
                    // e.g. the stream was already read another way: there is nothing to drop
                    upstreamDone = true;
                }
            }
            drain();
        });
    }

    private void drain() {
        while (!upstreamDone && upstreamReady && discardedBytes <= discardLimit) {
            try {
                int n = upstream().read(discardBuffer);
                if (n == -1) {
                    upstreamDone = true;
                } else {
                    discardedBytes += n;
                    upstreamReady = upstream().isReady();
                }
            } catch (IOException e) {
                upstreamDone = true;
            }
        }
        if (upstreamDone || discardedBytes > discardLimit) {
            Runnable done = discarded;
            discarded = null;
            if (done != null) {
                done.run();
            }
        }
    }

    @Override
    public void onDataAvailable() {
        submit(() -> {
            upstreamReady = upstream().isReady();
            if (discarded != null) {
                drain();
            } else {
                forwardSome();
            }
        });
    }

    @Override
    public void onAllDataRead() {
        submit(() -> {
            upstreamDone = true;
            if (discarded != null) {
                drain();
            } else {
                forwardSome();
            }
        });
    }

    @Override
    public void onError(Throwable t) {
        submit(() -> {
            error = t;
            upstreamDone = true;
            if (discarded != null) {
                drain();
            } else {
                forwardSome();
            }
        });
    }

    private enum WorkState {
        IDLE,
        WORKING,
        WORKING_PENDING_TASKS
    }
}
