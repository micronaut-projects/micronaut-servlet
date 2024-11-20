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
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link ServletInputStream} as a {@link Publisher}.
 *
 * @since 4.13.0
 * @author Jonas Konrad
 */
final class ServletStreamPublisher implements Publisher<ByteBuffer>, Subscription, ReadListener {
    private static final Logger LOG = LoggerFactory.getLogger(ServletStreamPublisher.class);

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicReference<WorkState> state = new AtomicReference<>(WorkState.IDLE);

    private final ThrowingSupplier<ServletInputStream, IOException> upstreamSupplier;

    private ServletInputStream upstream;
    private long demand;
    private boolean upstreamListenerRegistered;
    private boolean upstreamReady;
    private boolean upstreamDone;
    private boolean downstreamDone;
    private Throwable error;
    private Subscriber<? super ByteBuffer> downstream;

    ServletStreamPublisher(ThrowingSupplier<ServletInputStream, IOException> upstreamSupplier) {
        this.upstreamSupplier = upstreamSupplier;
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
                    upstream.setReadListener(this);
                } catch (IOException e) {
                    onError(e);
                }
            } else {
                forwardSome();
            }
        });
    }

    private void forwardSome() {
        while (!downstreamDone && demand > 0 && (upstreamReady || upstreamDone)) {
            if (!upstreamDone) {
                try {
                    byte[] arr = new byte[4096];
                    int n = upstream.read(arr);
                    if (n == -1) {
                        upstreamDone = true;
                    } else {
                        demand--;
                        downstream.onNext(ByteBuffer.wrap(arr, 0, n));
                        upstreamReady = upstream.isReady();
                    }
                } catch (IOException e) {
                    error = e;
                    upstreamDone = true;
                }
            }
            if (upstreamDone) {
                downstreamDone = true;
                if (error != null) {
                    downstream.onError(error);
                } else {
                    downstream.onComplete();
                }
            }
        }
    }

    @Override
    public void cancel() {
        submit(() -> {
            if (upstream != null) {
                try {
                    upstream.close();
                } catch (IOException e) {
                    LOG.debug("Failed to close request body for cancellation", e);
                }
            }
            downstreamDone = true;
        });
    }

    @Override
    public void onDataAvailable() {
        submit(() -> {
            upstreamReady = upstream.isReady();
            forwardSome();
        });
    }

    @Override
    public void onAllDataRead() {
        submit(() -> {
            upstreamDone = true;
            forwardSome();
        });
    }

    @Override
    public void onError(Throwable t) {
        submit(() -> {
            error = t;
            upstreamDone = true;
            forwardSome();
        });
    }

    private enum WorkState {
        IDLE,
        WORKING,
        WORKING_PENDING_TASKS
    }
}
