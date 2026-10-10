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
package io.micronaut.servlet.http.server;

import io.micronaut.context.reload.RequestAdmission;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The gate of a servlet server kept across the restarts of the application in development mode, shared by the
 * containers. It hands each request to the running generation of the application, counting it until it completes, and
 * holds a request, to any mapping, Micronaut's or a plain servlet's, while the development launcher's
 * {@link RequestAdmission} says a batch of changes compiles or applies, and while one generation stops and the next
 * starts. A held request is answered with a 503 and a {@code Retry-After} after {@link #holdTimeout()}. A stopping
 * generation is drained: its requests in flight may finish on it, for {@link #drainTimeout()} at most.
 *
 * <p>A request held while a batch is in progress is admitted once the batch is done, or as a restart is about to stop
 * the running generation: it is counted on the generation that runs then, on the thread that admits it, so that a
 * restart drains it there, as {@link RequestAdmission} has it. A request that arrives while no generation runs waits
 * for the next one.</p>
 *
 * <p>The gate holds what a generation serves, its handlers, context or deployment, only while that generation runs,
 * and the launcher's admission, which holds no generation. Only development code of the containers creates a gate: it
 * is never on the path of a request in production.</p>
 *
 * @param <G> What a generation serves
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
public final class DevelopmentRequestGate<G> {

    /**
     * The {@code Retry-After} of a request answered with a 503, in seconds.
     */
    public static final String RETRY_AFTER_SECONDS = "1";
    /**
     * How long a request waits without a development launcher, which otherwise sets it.
     */
    static final Duration DEFAULT_HOLD_TIMEOUT = Duration.ofSeconds(30);
    /**
     * How long a restart waits for the requests in flight without a development launcher, which otherwise sets it.
     */
    static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger LOG = LoggerFactory.getLogger(DevelopmentRequestGate.class);

    private final Object lock = new Object();
    /**
     * The requests waiting for the next generation.
     */
    private final List<HeldRequest<G>> held = new ArrayList<>();
    private volatile @Nullable Generation<G> current;
    /**
     * Whether a request may wait for the next generation: the server is kept, and not closed.
     */
    private boolean open;
    private volatile @Nullable RequestAdmission admission;

    /**
     * Sets the admission of the development launcher, which holds requests while a batch of changes is in progress and
     * carries the hold and drain timeouts; none without a launcher, when the defaults of 30 and 10 seconds apply.
     *
     * @param admission The admission, if any
     */
    public void admission(@Nullable RequestAdmission admission) {
        this.admission = admission;
    }

    /**
     * @return How long a request waits, for a batch or for the next generation, before it is answered with a 503
     */
    public Duration holdTimeout() {
        RequestAdmission launcher = admission;
        return launcher != null ? launcher.holdTimeout() : DEFAULT_HOLD_TIMEOUT;
    }

    /**
     * @return How long a stopping generation waits for its requests in flight
     */
    public Duration drainTimeout() {
        RequestAdmission launcher = admission;
        return launcher != null ? launcher.drainTimeout() : DEFAULT_DRAIN_TIMEOUT;
    }

    /**
     * Opens the gate: the server is kept, and the requests that arrive while no generation runs wait for the next one.
     */
    public void open() {
        synchronized (lock) {
            open = true;
        }
    }

    /**
     * @return The running generation, if any
     */
    public @Nullable Generation<G> current() {
        return current;
    }

    /**
     * Admits a request to the running generation, which counts it until {@link Generation#exit()}. Otherwise the request
     * the supplier creates is held: until the batch in progress is admitted, or until the next generation runs, and
     * then {@link HeldRequest#resume(Generation)}d; or it is answered with a 503 at once when the gate is closed. The
     * caller of a held request calls {@link #expire(HeldRequest)} after {@link #holdTimeout()}.
     *
     * @param waiting Creates the request to hold
     * @return The running generation, entered, or null when the request is held or answered
     */
    public @Nullable Generation<G> admit(Supplier<? extends HeldRequest<G>> waiting) {
        RequestAdmission launcher = admission;
        if (launcher != null && !launcher.isAdmitted()) {
            // a batch of changes compiles or applies: the running generation may be about to be replaced
            HeldRequest<G> request = waiting.get();
            launcher.whenAdmitted().thenRun(() -> admitted(request));
            return null;
        }
        while (true) {
            Generation<G> running = current;
            if (running != null && running.enter()) {
                return running;
            }
            HeldRequest<G> request = waiting.get();
            boolean holding;
            synchronized (lock) {
                Generation<G> now = current;
                if (now != null && now != running) {
                    // the next generation started meanwhile
                    continue;
                }
                holding = open;
                if (holding) {
                    held.add(request);
                }
            }
            if (!holding) {
                request.unavailable("The application is not running");
            }
            return null;
        }
    }

    /**
     * A held request waited past the hold timeout: it is answered with a 503, unless it was resumed meanwhile.
     *
     * @param request The request
     */
    public void expire(HeldRequest<G> request) {
        synchronized (lock) {
            held.remove(request);
        }
        request.unavailable("The application is restarting and did not finish within " + holdTimeout().toMillis() + " ms; retry shortly.");
    }

    /**
     * The next generation runs: it serves the requests that arrive from now on, and the held ones are resumed.
     *
     * @param served What the generation serves, started
     * @return The generation
     */
    public Generation<G> start(G served) {
        Generation<G> generation = new Generation<>(served);
        List<HeldRequest<G>> waiting;
        synchronized (lock) {
            current = generation;
            open = true;
            waiting = new ArrayList<>(held);
            held.clear();
        }
        for (HeldRequest<G> request : waiting) {
            if (request.claim()) {
                request.resume(null);
            }
        }
        return generation;
    }

    /**
     * The generation stops serving: the requests that arrive from now on wait for the next one.
     *
     * @param generation The generation
     * @return A future that completes once no request of the generation is in flight
     */
    public CompletableFuture<Void> retire(Generation<G> generation) {
        synchronized (lock) {
            if (current == generation) {
                current = null;
            }
        }
        return generation.retire();
    }

    /**
     * Retires the generation and waits for its requests in flight, for {@link #drainTimeout()} at most.
     *
     * @param generation The generation
     */
    public void drain(Generation<G> generation) {
        CompletableFuture<Void> idle = retire(generation);
        Duration timeout = drainTimeout();
        try {
            idle.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warn("Requests were still in flight on the stopping generation after {} ms; it stops anyway (micronaut.dev.requests.drain-timeout)",
                timeout.toMillis());
        } catch (ExecutionException e) {
            LOG.debug("Draining the stopping generation failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Closes the gate: the server is released, the held requests are answered with a 503, and so are those that arrive
     * until it opens again.
     *
     * @param message Why the requests are not served
     * @return The generation that ran, retired, to stop, if any
     */
    public @Nullable Generation<G> close(String message) {
        Generation<G> running;
        List<HeldRequest<G>> waiting;
        synchronized (lock) {
            open = false;
            running = current;
            current = null;
            waiting = new ArrayList<>(held);
            held.clear();
        }
        for (HeldRequest<G> request : waiting) {
            request.unavailable(message);
        }
        if (running != null) {
            running.retire();
        }
        return running;
    }

    private void admitted(HeldRequest<G> request) {
        if (!request.claim()) {
            // answered with a 503 meanwhile
            return;
        }
        // counted on the generation that runs as the request is admitted, before a restart drains it
        Generation<G> running = current;
        request.resume(running != null && running.enter() ? running : null);
    }

    /**
     * Runs the given action with the loader of the given class as the context class loader of the current thread: what
     * a kept server creates or starts keeps the context class loader of the creating thread, as the threads it starts,
     * and a generation's loader would keep that generation.
     *
     * @param owner A class of the container's integration
     * @param action The action
     * @param <T> The result type
     * @param <E> The exception type
     * @return The result
     * @throws E If the action fails
     */
    public static <T, E extends Exception> T withLoaderOf(Class<?> owner, Action<T, E> action) throws E {
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        thread.setContextClassLoader(owner.getClassLoader());
        try {
            return action.run();
        } finally {
            thread.setContextClassLoader(loader);
        }
    }

    /**
     * An action run by {@link #withLoaderOf(Class, Action)}.
     *
     * @param <T> The result type
     * @param <E> The exception type
     */
    @FunctionalInterface
    public interface Action<T, E extends Exception> {
        /**
         * @return The result
         * @throws E If the action fails
         */
        T run() throws E;
    }

    /**
     * A request waiting for a batch to be admitted or for the next generation: resumed once it may proceed, or answered
     * with a 503, whichever comes first.
     *
     * @param <G> What a generation serves
     */
    public abstract static class HeldRequest<G> {
        private final AtomicBoolean claimed = new AtomicBoolean();

        /**
         * Claims the request, once: to resume it or to answer it with a 503.
         *
         * @return Whether the request was claimed now
         */
        public final boolean claim() {
            if (!claimed.compareAndSet(false, true)) {
                return false;
            }
            claimed();
            return true;
        }

        /**
         * Answers the request with a 503 and a {@code Retry-After}, unless it was claimed already.
         *
         * @param message The body
         */
        public final void unavailable(String message) {
            if (claim()) {
                reject(message);
            }
        }

        /**
         * The request was claimed: a scheduled timeout can be cancelled.
         */
        protected void claimed() {
        }

        /**
         * Resumes the claimed request: on the given generation, which counted it already, or, when none is given,
         * through the gate again.
         *
         * @param entered The generation that counted the request, if any
         */
        protected abstract void resume(@Nullable Generation<G> entered);

        /**
         * Answers the claimed request with a 503 and a {@code Retry-After} of {@value DevelopmentRequestGate#RETRY_AFTER_SECONDS}.
         *
         * @param message The body
         */
        protected abstract void reject(String message);
    }

    /**
     * A generation of the application, as the gate serves it: each of its requests is counted, so that it can be
     * waited for as the generation stops.
     *
     * @param <G> What the generation serves
     */
    public static final class Generation<G> {
        private final G served;
        private final AtomicInteger active = new AtomicInteger();
        private final CompletableFuture<Void> idle = new CompletableFuture<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile boolean retired;

        private Generation(G served) {
            this.served = served;
        }

        /**
         * @return What the generation serves
         */
        public G served() {
            return served;
        }

        /**
         * Counts a request, unless the generation retired.
         *
         * @return Whether the request was counted
         */
        public boolean enter() {
            active.incrementAndGet();
            if (retired) {
                exit();
                return false;
            }
            return true;
        }

        /**
         * A request counted by {@link #enter()} completed.
         */
        public void exit() {
            if (active.decrementAndGet() == 0 && retired) {
                idle.complete(null);
            }
        }

        /**
         * Marks the generation as stopped, once.
         *
         * @return Whether it was stopped now, and what it serves must be stopped
         */
        public boolean stop() {
            retire();
            return stopped.compareAndSet(false, true);
        }

        private CompletableFuture<Void> retire() {
            retired = true;
            if (active.get() == 0) {
                idle.complete(null);
            }
            return idle;
        }
    }
}
