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
package io.micronaut.http.poja.apache;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import org.jspecify.annotations.Nullable;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The body of a request read from the shared session stream. It enforces {@code micronaut.server.max-request-size}
 * while the body is read, and {@link #discardRemaining()} consumes what the application left unread so that the
 * next request is parsed from where this body ends, never from inside it.
 *
 * <p>The application may read the body on another thread (a reactive consumer on the blocking executor) and keep
 * reading after the response has been sent, so reads and the discard are serialized on this stream, and once the
 * discard has started the application's reads fail instead of interleaving with it.</p>
 *
 * <p>{@link #close()} does not close the framing stream: closing a HttpCore {@code ContentLengthInputStream} or
 * {@code ChunkedInputStream} drains it without a bound, and the session stream itself belongs to the next
 * request.</p>
 */
@Internal
final class ApacheRequestBodyInputStream extends FilterInputStream {

    // the buffer size used to read and throw away input
    static final int DISCARD_BUFFER_SIZE = 8192;

    private final long maxBodySize;
    private final byte[] single = new byte[1];
    // serializes the application's reads with the runtime's discard, which may run on different threads
    private final ReentrantLock lock = new ReentrantLock();
    private final @Nullable ContinueHandler continueHandler;
    private long consumed;
    private boolean exceeded;
    private boolean failed;
    private volatile boolean finished;
    private boolean discarding;
    private boolean continueSent;

    /**
     * @param delegate The framing stream (content length or chunked)
     * @param maxBodySize The maximum number of body bytes, or a negative value for no limit
     * @param continueHandler Sends {@code 100 Continue} before the body is first read, when the request expects it
     */
    ApacheRequestBodyInputStream(InputStream delegate, long maxBodySize, @Nullable ContinueHandler continueHandler) {
        super(delegate);
        this.maxBodySize = maxBodySize;
        this.continueHandler = continueHandler;
    }

    @Override
    public int read() throws IOException {
        lock.lock();
        try {
            int n;
            do {
                n = read(single, 0, 1);
            } while (n == 0);
            return n < 0 ? -1 : single[0] & 0xFF;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        lock.lock();
        try {
            if (discarding) {
                throw new IOException("The request has completed and its body is no longer available");
            }
            return readBody(b, off, len);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        byte[] buffer = new byte[(int) Math.min(n, DISCARD_BUFFER_SIZE)];
        long skipped = 0;
        while (skipped < n) {
            int read = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
            if (read < 0) {
                break;
            }
            skipped += read;
        }
        return skipped;
    }

    @Override
    public int available() throws IOException {
        lock.lock();
        try {
            return discarding || finished ? 0 : in.available();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return Whether the body has been read to its end
     */
    boolean isFinished() {
        // without the lock: an application thread may hold it while blocked in a read
        return finished;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void close() {
        // the framing stream is drained by discardRemaining, with the size limit applied
    }

    /**
     * Consume the rest of the body, within the size limit. Reads by the application fail from here on.
     *
     * @return {@code true} if the body was read to its end and the session stream is positioned at the next
     * request, {@code false} if the connection has to be closed
     */
    boolean discardRemaining() {
        lock.lock();
        try {
            return discardLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Take the body away from the application without reading it, when the connection is closed rather than
     * reused. Reads by the application fail from here on.
     *
     * @return {@code true} if no application read is in progress, so the connection input may be read directly,
     * {@code false} if one is, and the connection input must be left alone
     */
    boolean tryDetach() {
        if (!lock.tryLock()) {
            return false;
        }
        try {
            discarding = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean discardLocked() {
        discarding = true;
        if (finished) {
            return true;
        }
        if (exceeded || failed) {
            return false;
        }
        if (continueHandler != null && !continueSent) {
            // The client waits for 100 Continue before it sends the body, and no longer will now that the final
            // response has been sent: whatever it sends next is not this body, so the connection cannot be reused.
            return false;
        }
        byte[] buffer = new byte[DISCARD_BUFFER_SIZE];
        try {
            int read;
            do {
                read = readBody(buffer, 0, buffer.length);
            } while (read >= 0);
            return true;
        } catch (IOException | RuntimeException e) {
            // a truncated or malformed body, or one over the size limit: the next request cannot be located
            return false;
        }
    }

    private int readBody(byte[] b, int off, int len) throws IOException {
        if (finished) {
            return -1;
        }
        if (exceeded) {
            throw new ContentLengthExceededException(maxBodySize, consumed);
        }
        int n;
        try {
            if (continueHandler != null && !continueSent && !discarding) {
                continueSent = continueHandler.sendContinue();
                if (!continueSent) {
                    // the final response went out first, so the client will not send the body it was holding back
                    throw new IOException("The request body is not available once the response has been sent");
                }
            }
            n = in.read(b, off, len);
        } catch (IOException e) {
            failed = true;
            throw e;
        }
        if (n < 0) {
            finished = true;
        } else if (n > 0) {
            consumed(n);
        }
        return n;
    }

    private void consumed(long n) {
        consumed += n;
        if (maxBodySize >= 0 && consumed > maxBodySize) {
            exceeded = true;
            throw new ContentLengthExceededException(maxBodySize, consumed);
        }
    }

    /**
     * Sends the interim {@code 100 Continue} response.
     */
    @FunctionalInterface
    interface ContinueHandler {

        /**
         * @return Whether the interim response was sent
         * @throws IOException If it could not be written
         */
        boolean sendContinue() throws IOException;
    }
}
