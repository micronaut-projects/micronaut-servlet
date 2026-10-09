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
package io.micronaut.servlet.jetty;

import io.micronaut.context.annotation.Retain;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Jetty server kept across the restarts of the application in development mode, with its connectors, so that the
 * port stays bound, and its thread pool. It is built once, by the first generation that uses it, and holds nothing of
 * a generation but the one it serves: its handler is a gate in front of the handlers of the running generation, its
 * servlet context with that generation's servlets, filters and listeners, its static resources and its compression,
 * which each generation builds anew with its own mappings, and its request log is the running generation's.
 *
 * <p>While one generation stops and the next starts, a request to any mapping, Micronaut's or not, waits for the next
 * generation, for {@value DevelopmentJettyReloader#HOLD_TIMEOUT} at most, after which it is answered with a 503 and a
 * {@code Retry-After}. The requests in flight on the stopping generation may finish on it, for
 * {@value DevelopmentJettyReloader#DRAIN_TIMEOUT} at most, before its handlers stop.</p>
 *
 * <p>Retained across restarts until a change under {@value HttpServerConfiguration#PREFIX}, which configures the
 * server, its connectors, Jetty and the access log, {@value MicronautServletConfiguration#PREFIX}, which configures the
 * thread pool, or {@value SslConfiguration#PREFIX}: the next generation then builds its own server, and this one is
 * stopped with the stopped context. A generation whose server listens on other ports, because the routes expose
 * other ones, does not use it either. When the requests run on virtual threads, the kept thread pool runs them on the
 * executor of the running generation.</p>
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@Retain(invalidatedBy = {HttpServerConfiguration.PREFIX, MicronautServletConfiguration.PREFIX, SslConfiguration.PREFIX})
@DevelopmentActive
final class DevelopmentJettyServer {

    private static final Logger LOG = LoggerFactory.getLogger(DevelopmentJettyServer.class);
    private static final String RETRY_AFTER_SECONDS = "1";
    private static final String GENERATION_ATTRIBUTE = DevelopmentJettyServer.class.getName() + ".generation";

    private final Object lock = new Object();
    private final GenerationGate gate = new GenerationGate();
    private final GenerationRequestLog requestLog = new GenerationRequestLog();
    /**
     * The requests waiting for the next generation.
     */
    private final List<HeldRequest> held = new ArrayList<>();
    private @Nullable Server server;
    private @Nullable String connectors;
    private volatile @Nullable Generation current;
    /**
     * The handlers of the generation that uses the server, until it starts.
     */
    private @Nullable Handler staged;
    private @Nullable RequestLog stagedLog;
    private @Nullable Executor stagedExecutor;
    /**
     * The virtual threads executor of the kept thread pool, which runs each task on the running generation's.
     */
    private final GenerationExecutor virtualThreads = new GenerationExecutor();
    private boolean closed;
    private volatile Duration holdTimeout = DevelopmentJettyReloader.DEFAULT_HOLD_TIMEOUT;
    private volatile Duration drainTimeout = DevelopmentJettyReloader.DEFAULT_DRAIN_TIMEOUT;

    /**
     * Created once, as the first generation starts, and kept from then on.
     */
    DevelopmentJettyServer() {
    }

    /**
     * Sets how long a request waits for the next generation, and how long a stopping generation waits for its requests.
     *
     * @param hold The hold timeout
     * @param drain The drain timeout
     */
    void timeouts(Duration hold, Duration drain) {
        holdTimeout = hold;
        drainTimeout = drain;
    }

    /**
     * The server a generation runs: the one kept, when it listens as the generation's own would, with the generation's
     * handlers staged in it; otherwise the generation's own, kept from now on in place of the one kept so far.
     *
     * @param built The server the generation built, with its connectors and its handlers, not started
     * @return The server to run
     */
    Server serve(Server built) {
        String signature = signature(built);
        Server released = null;
        synchronized (lock) {
            Server kept = server;
            if (kept != null && kept.isStarted() && signature.equals(connectors)) {
                stage(built);
                LOG.debug("Jetty server on {} retained across the restart", signature);
                return kept;
            }
            if (kept != null) {
                LOG.debug("The Jetty server listens on {} rather than {}: the one kept is stopped", signature, connectors);
                released = kept;
            }
            stage(built);
            if (built.getThreadPool() instanceof QueuedThreadPool pool && pool.getVirtualThreadsExecutor() != null) {
                // the requests run on virtual threads of the generation's executor, which the kept pool must not keep
                pool.setVirtualThreadsExecutor(virtualThreads);
            }
            built.setHandler(gate);
            // whether a generation logs requests may change from one generation to the next, by a request log bean
            built.setRequestLog(requestLog);
            server = built;
            connectors = signature;
            closed = false;
        }
        stop(released);
        return built;
    }

    /**
     * Whether the given server is the one kept here.
     *
     * @param candidate The server
     * @return True if kept here
     */
    boolean holds(Server candidate) {
        synchronized (lock) {
            return server == candidate;
        }
    }

    /**
     * Starts the server, unless it runs already, then the handlers the generation staged, which serve its requests and
     * those held for it from then on.
     *
     * @return The generation
     * @throws Exception If the server or the handlers cannot start
     */
    Generation start() throws Exception {
        Server kept;
        Handler handler;
        RequestLog log;
        Executor executor;
        synchronized (lock) {
            kept = server;
            handler = staged;
            log = stagedLog;
            executor = stagedExecutor;
            staged = null;
            stagedLog = null;
            stagedExecutor = null;
        }
        if (kept == null || handler == null) {
            throw new IllegalStateException("No generation staged its handlers in the retained Jetty server");
        }
        if (!kept.isStarted()) {
            kept.start();
        }
        handler.setServer(kept);
        if (log instanceof LifeCycle cycle) {
            cycle.start();
        }
        handler.start();
        Generation generation = new Generation(handler, log, executor);
        List<HeldRequest> waiting;
        synchronized (lock) {
            current = generation;
            waiting = new ArrayList<>(held);
            held.clear();
        }
        for (HeldRequest request : waiting) {
            dispatch(kept, request);
        }
        return generation;
    }

    /**
     * The generation stops serving: the requests that arrive from now on wait for the next one.
     *
     * @param generation The generation
     * @return A future that completes once no request of the generation is in flight
     */
    CompletableFuture<Void> retire(Generation generation) {
        synchronized (lock) {
            if (current == generation) {
                current = null;
            }
        }
        return generation.retire();
    }

    /**
     * Stops the handlers of a generation, once the requests in flight on it finished, or after the drain timeout.
     *
     * @param generation The generation
     */
    void stop(Generation generation) {
        CompletableFuture<Void> idle = retire(generation);
        Duration timeout = drainTimeout;
        try {
            idle.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warn("Requests were still in flight on the stopping generation after {} ms; its handlers stop anyway ({})",
                timeout.toMillis(), DevelopmentJettyReloader.DRAIN_TIMEOUT);
        } catch (ExecutionException e) {
            LOG.debug("Draining the stopping generation failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        generation.stop();
    }

    /**
     * Stops the server: a change released it, or the development runtime closes.
     */
    @PreDestroy
    void close() {
        synchronized (lock) {
            closed = true;
        }
        release();
    }

    private void release() {
        Server released;
        Generation running;
        List<HeldRequest> waiting;
        synchronized (lock) {
            released = server;
            running = current;
            server = null;
            connectors = null;
            current = null;
            staged = null;
            stagedLog = null;
            stagedExecutor = null;
            waiting = new ArrayList<>(held);
            held.clear();
        }
        for (HeldRequest request : waiting) {
            request.unavailable("The application stopped");
        }
        if (running != null) {
            running.stop();
        }
        stop(released);
    }

    private void stage(Server built) {
        staged = built.getHandler();
        stagedLog = built.getRequestLog();
        stagedExecutor = built.getThreadPool() instanceof QueuedThreadPool pool ? pool.getVirtualThreadsExecutor() : null;
        // the generation's server is not started: it keeps nothing the kept one needs
        built.setHandler((Handler) null);
        built.setRequestLog(null);
    }

    private static void stop(@Nullable Server released) {
        if (released == null) {
            return;
        }
        try {
            released.stop();
        } catch (Exception e) {
            LOG.debug("Cannot stop the released Jetty server", e);
        }
    }

    /**
     * What a server listens on: a generation that listens on the same addresses, with the same protocols, can use the
     * kept one.
     */
    private static String signature(Server server) {
        StringBuilder signature = new StringBuilder();
        for (Connector connector : server.getConnectors()) {
            signature.append('[').append(connector.getClass().getName());
            if (connector instanceof NetworkConnector network) {
                signature.append(' ').append(network.getHost()).append(':').append(network.getPort());
            }
            for (ConnectionFactory factory : connector.getConnectionFactories()) {
                signature.append(' ').append(factory.getProtocol());
            }
            signature.append(']');
        }
        return signature.toString();
    }

    private @Nullable Generation hold(Request request, Response response, Callback callback) {
        HeldRequest waiting = new HeldRequest(request, response, callback);
        Server kept;
        synchronized (lock) {
            Generation running = current;
            if (running != null) {
                return running;
            }
            kept = server;
            if (kept != null && !closed) {
                held.add(waiting);
            }
        }
        if (kept == null) {
            waiting.unavailable("The application is not running");
            return null;
        }
        Duration timeout = holdTimeout;
        waiting.timeout = kept.getScheduler().schedule(() -> {
            synchronized (lock) {
                held.remove(waiting);
            }
            waiting.unavailable("The application is restarting and did not finish within " + timeout.toMillis() + " ms; retry shortly.");
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        return null;
    }

    private void dispatch(Server kept, HeldRequest request) {
        try {
            kept.getThreadPool().execute(() -> {
                if (request.claim()) {
                    try {
                        if (!gate.handleOn(current, request.request, request.response, request.callback)) {
                            Response.writeError(request.request, request.response, request.callback, HttpStatus.NOT_FOUND_404);
                        }
                    } catch (Throwable e) {
                        Response.writeError(request.request, request.response, request.callback, e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            request.unavailable("The server is stopping");
        }
    }

    /**
     * The handler of the kept server: hands each request to the handlers of the running generation, or holds it until
     * the next one runs.
     */
    private final class GenerationGate extends Handler.Abstract {

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            Generation running = current;
            if (running == null) {
                running = hold(request, response, callback);
                if (running == null) {
                    return true;
                }
            }
            return handleOn(running, request, response, callback);
        }

        boolean handleOn(@Nullable Generation running, Request request, Response response, Callback callback) throws Exception {
            if (running == null || !running.enter()) {
                // retired meanwhile: waits for the next one
                Generation next = hold(request, response, callback);
                return next == null || handleOn(next, request, response, callback);
            }
            // the request is logged by the request log of the generation that served it, even once that one retired
            request.setAttribute(GENERATION_ATTRIBUTE, running);
            TrackedCallback tracked = new TrackedCallback(callback, running);
            boolean handled = false;
            try {
                handled = running.handler.handle(request, response, tracked);
                return handled;
            } finally {
                if (!handled) {
                    tracked.release();
                }
            }
        }
    }

    /**
     * Logs each request with the request log of the generation that served it, or of the running one for a request no
     * generation served, as one answered with a 503 while none ran.
     */
    private final class GenerationRequestLog implements RequestLog {
        @Override
        public void log(Request request, Response response) {
            Generation running = request.getAttribute(GENERATION_ATTRIBUTE) instanceof Generation served ? served : current;
            RequestLog log = running == null ? null : running.requestLog;
            if (log != null) {
                log.log(request, response);
            }
        }
    }

    /**
     * Runs the tasks the kept thread pool gives virtual threads on the virtual threads executor of the running
     * generation, or on virtual threads of its own while none runs, or once that generation's executor no longer
     * accepts them.
     */
    private final class GenerationExecutor implements Executor {
        private final Executor fallback = VirtualThreads.getNamedVirtualThreadsExecutor("jetty-dev-");

        @Override
        public void execute(Runnable task) {
            Generation running = current;
            Executor executor = running == null ? null : running.executor;
            if (executor != null) {
                try {
                    executor.execute(task);
                    return;
                } catch (RejectedExecutionException e) {
                    // the executor of a stopping generation
                }
            }
            fallback.execute(task);
        }
    }

    /**
     * What a generation serves: its handlers, with its servlet context, and its request log. Each of its requests is
     * counted, so that it can be waited for as the generation stops.
     */
    static final class Generation {
        private final Handler handler;
        private final @Nullable RequestLog requestLog;
        private final @Nullable Executor executor;
        private final AtomicInteger active = new AtomicInteger();
        private final CompletableFuture<Void> idle = new CompletableFuture<>();
        private volatile boolean retired;
        private final AtomicBoolean stopped = new AtomicBoolean();

        Generation(Handler handler, @Nullable RequestLog requestLog, @Nullable Executor executor) {
            this.handler = handler;
            this.requestLog = requestLog;
            this.executor = executor;
        }

        boolean enter() {
            active.incrementAndGet();
            if (retired) {
                exit();
                return false;
            }
            return true;
        }

        void exit() {
            if (active.decrementAndGet() == 0 && retired) {
                idle.complete(null);
            }
        }

        CompletableFuture<Void> retire() {
            retired = true;
            if (active.get() == 0) {
                idle.complete(null);
            }
            return idle;
        }

        void stop() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            retired = true;
            try {
                handler.stop();
            } catch (Exception e) {
                LOG.debug("Cannot stop the handlers of the stopping generation", e);
            }
            if (requestLog instanceof LifeCycle cycle) {
                try {
                    cycle.stop();
                } catch (Exception e) {
                    LOG.debug("Cannot stop the request log of the stopping generation", e);
                }
            }
        }
    }

    /**
     * Counts a request of a generation as finished once it completes.
     */
    private static final class TrackedCallback implements Callback {
        private final Callback delegate;
        private final Generation generation;
        private final AtomicBoolean released = new AtomicBoolean();

        TrackedCallback(Callback delegate, Generation generation) {
            this.delegate = delegate;
            this.generation = generation;
        }

        @Override
        public void succeeded() {
            release();
            delegate.succeeded();
        }

        @Override
        public void failed(Throwable x) {
            release();
            delegate.failed(x);
        }

        @Override
        public InvocationType getInvocationType() {
            return delegate.getInvocationType();
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                generation.exit();
            }
        }
    }

    /**
     * A request waiting for the next generation: dispatched to it once it runs, or answered with a 503, whichever comes
     * first.
     */
    private static final class HeldRequest {
        private final Request request;
        private final Response response;
        private final Callback callback;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private volatile Scheduler.@Nullable Task timeout;

        HeldRequest(Request request, Response response, Callback callback) {
            this.request = request;
            this.response = response;
            this.callback = callback;
        }

        boolean claim() {
            if (!claimed.compareAndSet(false, true)) {
                return false;
            }
            Scheduler.Task task = timeout;
            if (task != null) {
                task.cancel();
            }
            return true;
        }

        void unavailable(String message) {
            if (!claim()) {
                return;
            }
            try {
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE_503);
                response.getHeaders().put(HttpHeader.RETRY_AFTER, RETRY_AFTER_SECONDS);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, message, callback);
            } catch (Throwable e) {
                callback.failed(e);
            }
        }
    }
}
