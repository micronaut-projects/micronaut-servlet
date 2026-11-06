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
package io.micronaut.servlet.websocket;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.execution.ExecutionFlow;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The messages of a connection to a WebSocket route of the route builder, handled like on the
 * Netty server: the first once the open handler is done, each next once a handler is done, at
 * most {@code maxConcurrentMessages} at the same time, in the order they were read.
 *
 * <p>At most {@code maxPendingMessages} wait for their turn, {@code 0} for no limit. Jakarta
 * WebSocket has no way to stop reading, so once that many wait, the thread of the container that
 * delivers the next message waits until there is room: the container reads nothing meanwhile,
 * the pings and the close included, like the Netty server holds them back. A client that drops
 * the connection meanwhile is noticed once there is room again, not at once like on Netty, which
 * reads on without decoding.</p>
 *
 * @author Denis Stepanov
 * @since 6.2.3
 */
@Internal
final class RouteMessageQueue {

    private final int maxConcurrentMessages;
    private final int maxPendingMessages;
    /**
     * The messages that wait for their turn, guarded by {@code this}.
     */
    private final ArrayDeque<Supplier<ExecutionFlow<?>>> pending = new ArrayDeque<>();
    /**
     * The handlers run in one thread at a time, see {@link #drain()}.
     */
    private final AtomicInteger work = new AtomicInteger();
    /**
     * The messages being handled, guarded by {@code this}.
     */
    private int handling;
    /**
     * Whether the open handler runs, guarded by {@code this}.
     */
    private boolean opening = true;
    /**
     * Whether the connection closed, guarded by {@code this}.
     */
    private boolean closed;

    /**
     * @param maxConcurrentMessages The most messages handled at the same time
     * @param maxPendingMessages    The most messages that wait for their turn, {@code 0} for no limit
     */
    RouteMessageQueue(int maxConcurrentMessages, int maxPendingMessages) {
        this.maxConcurrentMessages = maxConcurrentMessages;
        this.maxPendingMessages = maxPendingMessages;
    }

    /**
     * A message was read: it is handled in its turn. Waits while the pending messages are full.
     *
     * @param message Handles the message, the flow completes when it is done with
     */
    void offer(Supplier<ExecutionFlow<?>> message) {
        synchronized (this) {
            while (!closed && maxPendingMessages > 0 && pending.size() >= maxPendingMessages) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // e.g. the container stops: the message is not handled
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (closed) {
                return;
            }
            pending.add(message);
        }
        drain();
    }

    /**
     * The open handler is done: the messages are handled.
     */
    void opened() {
        synchronized (this) {
            opening = false;
        }
        drain();
    }

    /**
     * The connection closed: no message is handled any more.
     *
     * @return The messages that waited for their turn, in order
     */
    List<Supplier<ExecutionFlow<?>>> close() {
        synchronized (this) {
            closed = true;
            List<Supplier<ExecutionFlow<?>>> waiting = new ArrayList<>(pending);
            pending.clear();
            // a container thread that waits for room delivers nothing
            notifyAll();
            return waiting;
        }
    }

    private void drain() {
        if (work.getAndIncrement() != 0) {
            // the thread that drains handles it: a handler done at once does not recurse
            return;
        }
        int missed = 1;
        do {
            Supplier<ExecutionFlow<?>> next;
            while ((next = next()) != null) {
                handle(next);
            }
            missed = work.addAndGet(-missed);
        } while (missed != 0);
    }

    private synchronized @Nullable Supplier<ExecutionFlow<?>> next() {
        if (closed || opening || handling >= maxConcurrentMessages) {
            return null;
        }
        Supplier<ExecutionFlow<?>> next = pending.poll();
        if (next != null) {
            handling++;
            // a container thread that waits for room delivers its message
            notifyAll();
        }
        return next;
    }

    private void handle(Supplier<ExecutionFlow<?>> message) {
        ExecutionFlow<?> handled;
        try {
            handled = message.get();
        } catch (RuntimeException e) {
            handled = ExecutionFlow.error(e);
        }
        handled.onComplete((ignored, error) -> done());
    }

    private void done() {
        synchronized (this) {
            handling--;
        }
        drain();
    }
}
