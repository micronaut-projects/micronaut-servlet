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
package io.micronaut.servlet.http.server.jdk.websocket;

import io.micronaut.core.annotation.Internal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The writing side of an upgraded connection: one frame at a time, whichever thread sends it, and
 * the asynchronous sends in the order they were made, on an executor of their own: never the bounded pool of
 * the server, whose workers the readers of the connections occupy.
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
final class WebSocketConnection {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketConnection.class);

    private final OutputStream out;
    private final Executor executor;
    private final Runnable abort;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Queue<Runnable> asyncSends = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean aborted = new AtomicBoolean();
    private volatile long lastActivity = System.nanoTime();
    /**
     * The close frame went out: nothing but a close frame may follow it (RFC 6455, 5.5.1).
     */
    private volatile boolean closeSent;
    /**
     * The opcode of a message sent in parts that is not complete yet, or {@code -1}.
     */
    private int partialOpcode = -1;

    /**
     * @param out      The stream of the connection
     * @param executor Runs the asynchronous sends, apart from the threads that read the connections
     * @param abort    Drops the connection
     */
    WebSocketConnection(OutputStream out, Executor executor, Runnable abort) {
        this.out = out;
        this.executor = executor;
        this.abort = abort;
    }

    /**
     * Sends a whole message, or a control frame.
     *
     * @param opcode  The opcode
     * @param payload The payload
     * @throws IOException If it could not be written, or the connection is closing
     */
    void send(int opcode, ByteBuffer payload) throws IOException {
        writeLock.lock();
        try {
            boolean control = opcode >= WebSocketFrames.OPCODE_CLOSE;
            if (!control && partialOpcode != -1) {
                throw new IllegalStateException("A message sent in parts is not complete");
            }
            write(true, opcode, payload);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Sends a part of a message.
     *
     * @param opcode  The opcode of the message
     * @param payload The part
     * @param last    Whether it is the last part
     * @throws IOException If it could not be written, or the connection is closing
     */
    void sendPart(int opcode, ByteBuffer payload, boolean last) throws IOException {
        writeLock.lock();
        try {
            int frameOpcode;
            if (partialOpcode == -1) {
                frameOpcode = opcode;
            } else if (partialOpcode == opcode) {
                frameOpcode = WebSocketFrames.OPCODE_CONTINUATION;
            } else {
                throw new IllegalStateException("A message of another type is being sent in parts");
            }
            write(last, frameOpcode, payload);
            partialOpcode = last ? -1 : opcode;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Sends the close frame, unless it went out already.
     *
     * @param payload The payload of the close frame
     * @return Whether this sent it
     * @throws IOException If it could not be written
     */
    boolean sendClose(ByteBuffer payload) throws IOException {
        writeLock.lock();
        try {
            if (closeSent) {
                return false;
            }
            write(true, WebSocketFrames.OPCODE_CLOSE, payload);
            closeSent = true;
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    private void write(boolean fin, int opcode, ByteBuffer payload) throws IOException {
        if (closeSent) {
            throw new IOException("The WebSocket connection is closing");
        }
        if (aborted.get()) {
            throw new IOException("The WebSocket connection is closed");
        }
        WebSocketFrames.write(out, fin, opcode, payload);
        out.flush();
        lastActivity = System.nanoTime();
    }

    /**
     * Runs a send after the asynchronous sends made before it, on the executor of the sends.
     *
     * @param send The send
     */
    void sendAsync(Runnable send) {
        asyncSends.add(send);
        drain();
    }

    private void drain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::drainSends);
        } catch (RejectedExecutionException e) {
            // the server is stopping: the sends fail rather than wait for an executor that never runs them
            draining.set(false);
            abort();
            drainSends();
        }
    }

    private void drainSends() {
        while (true) {
            Runnable send = asyncSends.poll();
            while (send != null) {
                run(send);
                send = asyncSends.poll();
            }
            draining.set(false);
            if (asyncSends.isEmpty() || !draining.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private static void run(Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            // a failing completion callback of the application must not stop the sends queued after it
            LOG.warn("Error completing an asynchronous WebSocket send: {}", e.getMessage(), e);
        }
    }

    /**
     * Records that the client sent something, for the idle timeout.
     */
    void touch() {
        lastActivity = System.nanoTime();
    }

    /**
     * @return When something was last read or written, in {@link System#nanoTime()} terms
     */
    long lastActivity() {
        return lastActivity;
    }

    /**
     * @return Whether the close frame went out
     */
    boolean isCloseSent() {
        // without the write lock, which a write blocked on the peer holds
        return closeSent;
    }

    /**
     * Drops the connection, which ends a read that is blocked on it.
     */
    void abort() {
        if (aborted.compareAndSet(false, true)) {
            try {
                abort.run();
            } catch (RuntimeException e) {
                LOG.debug("Error dropping a WebSocket connection: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * @return Whether the connection was dropped
     */
    boolean isAborted() {
        return aborted.get();
    }
}
