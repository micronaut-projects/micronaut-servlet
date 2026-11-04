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
import io.micronaut.context.reload.RequestAdmission;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import io.micronaut.servlet.http.server.DevelopmentRequestGate;
import io.micronaut.servlet.http.server.DevelopmentRequestGate.Generation;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Jetty server kept across the restarts of the application in development mode, with its connectors, so that the
 * port stays bound, and its thread pool. It is built once, by the first generation that uses it, and holds nothing of
 * a generation but the one it serves: its handler is a gate in front of the handlers of the running generation, its
 * servlet context with that generation's servlets, filters and listeners, its static resources and its compression,
 * which each generation builds anew with its own mappings, and its request log is the running generation's.
 *
 * <p>The gate is a {@link DevelopmentRequestGate}: while the development launcher's {@link RequestAdmission} says a
 * batch of changes compiles or applies, and while one generation stops and the next starts, a request to any mapping,
 * Micronaut's or not, waits, for {@link RequestAdmission#holdTimeout()} at most, after which it is answered with a 503
 * and a {@code Retry-After}. The requests in flight on the stopping generation may finish on it, for
 * {@link RequestAdmission#drainTimeout()} at most, before its handlers stop.</p>
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
final class RetainedJettyServer {

    private static final Logger LOG = LoggerFactory.getLogger(RetainedJettyServer.class);
    private static final String GENERATION_ATTRIBUTE = RetainedJettyServer.class.getName() + ".generation";

    private final Object lock = new Object();
    private final DevelopmentRequestGate<Served> gate = new DevelopmentRequestGate<>();
    private final GateHandler handler = new GateHandler();
    private final GenerationRequestLog requestLog = new GenerationRequestLog();
    private @Nullable Server server;
    private @Nullable String connectors;
    /**
     * What the generation that uses the server serves, until it starts.
     */
    private @Nullable Served staged;
    /**
     * The virtual threads executor of the kept thread pool, which runs each task on the running generation's.
     */
    private final GenerationExecutor virtualThreads = new GenerationExecutor();

    /**
     * Created once, as the first generation starts, and kept from then on.
     */
    RetainedJettyServer() {
    }

    /**
     * Sets the admission of the development launcher, which holds requests while a batch of changes is in progress and
     * carries the hold and drain timeouts; none without a launcher, when the defaults apply.
     *
     * @param admission The admission, if any
     */
    void admission(@Nullable RequestAdmission admission) {
        gate.admission(admission);
    }

    /**
     * The server a generation runs: the one kept, when it listens as the generation's own would, with the generation's
     * handlers staged in it; otherwise the generation's own, kept from now on in place of the one kept so far.
     *
     * <p>A generation whose server gets connectors of its own once built, for the ports its routes expose or for
     * connector configurations, runs that server, which is not kept, and the one kept so far is stopped.</p>
     *
     * @param built The server the generation built, with its connectors and its handlers, not started
     * @param retainable Whether the server can be kept: it gets no other connector once built
     * @return The server to run
     */
    Server serve(Server built, boolean retainable) {
        if (!retainable) {
            LOG.debug("The Jetty server gets connectors of its own once built, for exposed ports or connector configurations: it is not retained");
            release();
            return built;
        }
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
            built.setHandler(handler);
            // whether a generation logs requests may change from one generation to the next, by a request log bean
            built.setRequestLog(requestLog);
            server = built;
            connectors = signature;
        }
        if (released != null) {
            closeGate();
            stop(released);
        }
        gate.open();
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
    Generation<Served> start() throws Exception {
        Server kept;
        Served served;
        synchronized (lock) {
            kept = server;
            served = staged;
            staged = null;
        }
        if (kept == null || served == null) {
            throw new IllegalStateException("No generation staged its handlers in the retained Jetty server");
        }
        if (!kept.isStarted()) {
            kept.start();
        }
        served.handler.setServer(kept);
        if (served.requestLog instanceof LifeCycle cycle) {
            cycle.start();
        }
        served.handler.start();
        return gate.start(served);
    }

    /**
     * The generation stops serving: the requests that arrive from now on wait for the next one.
     *
     * @param generation The generation
     * @return A future that completes once no request of the generation is in flight
     */
    CompletableFuture<Void> retire(Generation<Served> generation) {
        return gate.retire(generation);
    }

    /**
     * Stops the handlers of a generation, once the requests in flight on it finished, or after the drain timeout.
     *
     * @param generation The generation
     */
    void stop(Generation<Served> generation) {
        gate.drain(generation);
        stopServed(generation);
    }

    /**
     * Stops the server: a change released it, or the development runtime closes.
     */
    @PreDestroy
    void close() {
        release();
    }

    private void release() {
        Server released;
        synchronized (lock) {
            released = server;
            server = null;
            connectors = null;
            staged = null;
        }
        closeGate();
        stop(released);
    }

    private void closeGate() {
        Generation<Served> running = gate.close("The application stopped");
        if (running != null) {
            stopServed(running);
        }
    }

    private static void stopServed(Generation<Served> generation) {
        if (!generation.stop()) {
            return;
        }
        Served served = generation.served();
        try {
            served.handler.stop();
        } catch (Exception e) {
            LOG.debug("Cannot stop the handlers of the stopping generation", e);
        }
        if (served.requestLog instanceof LifeCycle cycle) {
            try {
                cycle.stop();
            } catch (Exception e) {
                LOG.debug("Cannot stop the request log of the stopping generation", e);
            }
        }
    }

    private void stage(Server built) {
        Handler handlers = built.getHandler();
        if (handlers == null) {
            throw new IllegalStateException("The generation's Jetty server has no handler");
        }
        staged = new Served(handlers, built.getRequestLog(),
            built.getThreadPool() instanceof QueuedThreadPool pool ? pool.getVirtualThreadsExecutor() : null);
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

    private @Nullable Server kept() {
        synchronized (lock) {
            return server;
        }
    }

    /**
     * What a generation serves: its handlers, with its servlet context, its request log, and the executor of its
     * virtual threads.
     *
     * @param handler The handlers
     * @param requestLog The request log
     * @param executor The executor of virtual threads
     */
    record Served(Handler handler, @Nullable RequestLog requestLog, @Nullable Executor executor) {
    }

    /**
     * The handler of the kept server: hands each request to the handlers of the running generation, or holds it in the
     * gate until it may proceed.
     */
    private final class GateHandler extends Handler.Abstract {

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            HeldRequest[] waiting = new HeldRequest[1];
            Generation<Served> running = gate.admit(() -> waiting[0] = new HeldRequest(request, response, callback));
            if (running == null) {
                schedule(waiting[0]);
                return true;
            }
            return handleEntered(running, request, response, callback);
        }

        boolean handleEntered(Generation<Served> running, Request request, Response response, Callback callback) throws Exception {
            // the request is logged by the request log of the generation that served it, even once that one retired
            request.setAttribute(GENERATION_ATTRIBUTE, running);
            TrackedCallback tracked = new TrackedCallback(callback, running);
            boolean handled = false;
            try {
                handled = running.served().handler.handle(request, response, tracked);
                return handled;
            } finally {
                if (!handled) {
                    tracked.release();
                }
            }
        }

        private void schedule(@Nullable HeldRequest waiting) {
            Server kept = kept();
            if (waiting == null || kept == null) {
                return;
            }
            long timeout = gate.holdTimeout().toMillis();
            waiting.timeout = kept.getScheduler().schedule(() -> gate.expire(waiting), timeout, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Logs each request with the request log of the generation that served it, or of the running one for a request no
     * generation served, as one answered with a 503 while none ran.
     */
    private final class GenerationRequestLog implements RequestLog {
        @Override
        @SuppressWarnings("unchecked")
        public void log(Request request, Response response) {
            Generation<Served> running = request.getAttribute(GENERATION_ATTRIBUTE) instanceof Generation<?> served
                ? (Generation<Served>) served
                : gate.current();
            RequestLog log = running == null ? null : running.served().requestLog;
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
            Generation<Served> running = gate.current();
            Executor executor = running == null ? null : running.served().executor;
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
     * Counts a request of a generation as finished once it completes.
     */
    private static final class TrackedCallback implements Callback {
        private final Callback delegate;
        private final Generation<Served> generation;
        private final AtomicBoolean released = new AtomicBoolean();

        TrackedCallback(Callback delegate, Generation<Served> generation) {
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
     * A request held by the gate: resumed on a thread of the kept pool once it may proceed, or answered with a 503,
     * whichever comes first.
     */
    private final class HeldRequest extends DevelopmentRequestGate.HeldRequest<Served> {
        private final Request request;
        private final Response response;
        private final Callback callback;
        private volatile Scheduler.@Nullable Task timeout;

        HeldRequest(Request request, Response response, Callback callback) {
            this.request = request;
            this.response = response;
            this.callback = callback;
        }

        @Override
        protected void claimed() {
            Scheduler.Task task = timeout;
            if (task != null) {
                task.cancel();
            }
        }

        @Override
        protected void resume(@Nullable Generation<Served> entered) {
            Server kept = kept();
            try {
                if (kept == null) {
                    throw new RejectedExecutionException("The server is stopped");
                }
                kept.getThreadPool().execute(() -> {
                    try {
                        boolean handled = entered != null
                            ? handler.handleEntered(entered, request, response, callback)
                            : handler.handle(request, response, callback);
                        if (!handled) {
                            Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
                        }
                    } catch (Throwable e) {
                        Response.writeError(request, response, callback, e);
                    }
                });
            } catch (RejectedExecutionException e) {
                if (entered != null) {
                    entered.exit();
                }
                reject("The server is stopping");
            }
        }

        @Override
        protected void reject(String message) {
            try {
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE_503);
                response.getHeaders().put(HttpHeader.RETRY_AFTER, DevelopmentRequestGate.RETRY_AFTER_SECONDS);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, message, callback);
            } catch (Throwable e) {
                callback.failed(e);
            }
        }
    }
}
