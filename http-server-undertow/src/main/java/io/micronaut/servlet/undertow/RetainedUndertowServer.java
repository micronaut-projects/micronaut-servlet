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
package io.micronaut.servlet.undertow;

import io.micronaut.context.annotation.Retain;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.context.reload.RequestAdmission;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import io.micronaut.servlet.http.server.DevelopmentRequestGate;
import io.micronaut.servlet.http.server.DevelopmentRequestGate.Generation;
import io.undertow.Undertow;
import io.undertow.connector.ByteBufferPool;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.SameThreadExecutor;
import io.undertow.util.StatusCodes;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.XnioExecutor;
import org.xnio.XnioWorker;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The Undertow server kept across the restarts of the application in development mode, with its listeners, so that
 * the ports stay bound, and its XNIO worker. It is built once, by the first generation that uses it, and holds nothing
 * of a generation but the one it serves: its root handler is a gate in front of the handler of the running generation,
 * the generation's servlet deployment with its compression, graceful shutdown and access log, which each generation
 * deploys anew in {@link Servlets#defaultContainer()}, and undeploys from it, as it stops.
 *
 * <p>The gate is a {@link DevelopmentRequestGate}: while the development launcher's {@link RequestAdmission} says a
 * batch of changes compiles or applies, and while one generation stops and the next starts, a request to any mapping,
 * Micronaut's or not, waits, without a thread, for {@link RequestAdmission#holdTimeout()} at most, after which it is
 * answered with a 503 and a {@code Retry-After}. The requests in flight on the stopping generation may finish on it, for
 * {@link RequestAdmission#drainTimeout()} at most, before its deployment stops.</p>
 *
 * <p>Retained across restarts until a change under {@value HttpServerConfiguration#PREFIX}, which configures the
 * server, its listeners, Undertow, its options and the access log, {@value MicronautServletConfiguration#PREFIX}, which
 * configures the worker threads, or {@value SslConfiguration#PREFIX}: the next generation then builds its own server,
 * and this one is stopped with the stopped context. A generation whose routes expose other ports does not use it
 * either. The server starts with the loader of Undertow's integration as the context class loader, since its XNIO
 * threads keep the one they start with. The JSR-356 containers of the deployments use its worker, and a buffer pool of
 * this bean, rather than Undertow's static default container, whose worker would start with a generation's loader.</p>
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@Retain(invalidatedBy = {HttpServerConfiguration.PREFIX, MicronautServletConfiguration.PREFIX, SslConfiguration.PREFIX})
@DevelopmentActive
final class RetainedUndertowServer {

    private static final Logger LOG = LoggerFactory.getLogger(RetainedUndertowServer.class);
    private static final HttpString RETRY_AFTER = new HttpString("Retry-After");
    private static final int WEBSOCKET_BUFFER_SIZE = 16 * 1024;

    private final Object lock = new Object();
    private final DevelopmentRequestGate<Served> gate = new DevelopmentRequestGate<>();
    private final GateHandler handler = new GateHandler();
    private final ByteBufferPool webSocketBuffers = new DefaultByteBufferPool(true, WEBSOCKET_BUFFER_SIZE);
    private @Nullable Undertow server;
    private @Nullable String listeners;
    /**
     * What the generation that uses the server serves, until it starts.
     */
    private @Nullable Served staged;

    /**
     * Created once, as the first generation starts, and kept from then on.
     */
    RetainedUndertowServer() {
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
     * deployment staged in it; otherwise one built from the generation's builder, with the gate as its root handler,
     * kept from now on in place of the one kept so far.
     *
     * @param builder The generation's builder, with its listeners, options and handler
     * @param served What the generation serves
     * @param signature What the generation's server listens on, beyond its configuration
     * @return The server to run
     */
    Undertow serve(Undertow.Builder builder, Served served, String signature) {
        Undertow released = null;
        Undertow serving;
        Served unstarted;
        synchronized (lock) {
            Undertow kept = server;
            // a deployment staged by a generation that never started is replaced
            unstarted = staged;
            staged = served;
            if (kept != null && kept.getWorker() != null && signature.equals(listeners)) {
                LOG.debug("Undertow server on {} retained across the restart", signature);
                serving = kept;
            } else {
                if (kept != null) {
                    LOG.debug("The Undertow server listens on {} rather than {}: the one kept is stopped", signature, listeners);
                }
                released = kept;
                // the generation's handler is served through the gate
                builder.setHandler(handler);
                serving = builder.build();
                server = serving;
                listeners = signature;
            }
        }
        if (unstarted != null) {
            undeploy(unstarted);
        }
        if (released != null) {
            closeGate();
            stop(released);
        }
        gate.open();
        return serving;
    }

    /**
     * Whether the given server is the one kept here.
     *
     * @param candidate The server
     * @return True if kept here
     */
    boolean holds(Undertow candidate) {
        synchronized (lock) {
            return server == candidate;
        }
    }

    /**
     * The worker of the kept server, which the JSR-356 containers of the deployments use.
     *
     * @return The worker, once the server started
     */
    @Nullable XnioWorker worker() {
        Undertow kept;
        synchronized (lock) {
            kept = server;
        }
        return kept == null ? null : kept.getWorker();
    }

    /**
     * @return The buffer pool the JSR-356 containers of the deployments use
     */
    ByteBufferPool webSocketBuffers() {
        return webSocketBuffers;
    }

    /**
     * Starts the server, unless it runs already, then serves the deployment the generation staged, which serves its
     * requests and those held for it from then on.
     *
     * @return The generation
     */
    Generation<Served> start() {
        Undertow kept;
        Served served;
        synchronized (lock) {
            kept = server;
            served = staged;
            staged = null;
        }
        if (kept == null || served == null) {
            throw new IllegalStateException("No generation staged its deployment in the retained Undertow server");
        }
        if (kept.getWorker() == null) {
            try {
                DevelopmentRequestGate.withLoaderOf(RetainedUndertowServer.class, () -> {
                    kept.start();
                    return null;
                });
            } catch (RuntimeException e) {
                // the generation fails to start: its deployment leaves the static container now, as nothing else will
                undeploy(served);
                throw e;
            }
        }
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
     * Undeploys the deployment of a generation, once the requests in flight on it finished, or after the drain timeout:
     * it stops, which closes its WebSocket sessions, and leaves Undertow's static container.
     *
     * @param generation The generation
     */
    void stop(Generation<Served> generation) {
        gate.drain(generation);
        undeploy(generation);
    }

    /**
     * Stops the server: a change released it, or the development runtime closes.
     */
    @PreDestroy
    void close() {
        Undertow released;
        Served unstarted;
        synchronized (lock) {
            released = server;
            unstarted = staged;
            server = null;
            listeners = null;
            staged = null;
        }
        closeGate();
        if (unstarted != null) {
            undeploy(unstarted);
        }
        stop(released);
        webSocketBuffers.close();
    }

    private void closeGate() {
        Generation<Served> running = gate.close("The application stopped");
        if (running != null) {
            undeploy(running);
        }
    }

    private static void undeploy(Generation<Served> generation) {
        if (generation.stop()) {
            undeploy(generation.served());
        }
    }

    /**
     * Stops a deployment and removes it from Undertow's static container, unless the next generation's took its name.
     *
     * @param served The deployment
     */
    static void undeploy(Served served) {
        DeploymentManager manager = served.manager();
        if (manager == null) {
            return;
        }
        // the deployment is gone once undeployed
        DeploymentInfo info = manager.getDeployment() == null ? null : manager.getDeployment().getDeploymentInfo();
        try {
            manager.stop();
        } catch (Exception e) {
            LOG.debug("Cannot stop the deployment of the stopping generation", e);
        }
        try {
            manager.undeploy();
        } catch (RuntimeException e) {
            LOG.debug("Cannot undeploy the deployment of the stopping generation", e);
        }
        if (info == null) {
            return;
        }
        ServletContainer container = Servlets.defaultContainer();
        synchronized (container) {
            if (container.getDeployment(info.getDeploymentName()) == manager) {
                container.removeDeployment(info);
            }
        }
    }

    private static void stop(@Nullable Undertow released) {
        if (released == null) {
            return;
        }
        try {
            released.stop();
        } catch (RuntimeException e) {
            LOG.debug("Cannot stop the released Undertow server", e);
        }
    }

    private @Nullable Undertow kept() {
        synchronized (lock) {
            return server;
        }
    }

    /**
     * What a generation serves: the root handler its factory built, and its servlet deployment.
     *
     * @param handler The handler
     * @param manager The deployment, if found
     */
    record Served(HttpHandler handler, @Nullable DeploymentManager manager) {
    }

    /**
     * The root handler of the kept server: hands each exchange to the handler of the running generation, or holds it
     * until it may proceed.
     */
    private final class GateHandler implements HttpHandler {

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            HeldExchange[] waiting = new HeldExchange[1];
            Generation<Served> running = gate.admit(() -> waiting[0] = new HeldExchange(exchange));
            if (running == null) {
                HeldExchange held = waiting[0];
                // the exchange stays open once this returns: it is resumed, or answered, from another thread
                exchange.dispatch(SameThreadExecutor.INSTANCE, held::ready);
                return;
            }
            handleEntered(running, exchange);
        }

        void handleEntered(Generation<Served> running, HttpServerExchange exchange) throws Exception {
            exchange.addExchangeCompleteListener((ended, next) -> {
                running.exit();
                next.proceed();
            });
            running.served().handler().handleRequest(exchange);
        }
    }

    /**
     * An exchange held by the gate. It is resumed, or answered with a 503, on its IO thread, once the call that held it
     * returned, since an exchange is only dispatched again from outside the handler that holds it.
     */
    private final class HeldExchange extends DevelopmentRequestGate.HeldRequest<Served> {
        private final HttpServerExchange exchange;
        private boolean ready;
        private @Nullable Runnable pending;
        private volatile XnioExecutor.@Nullable Key timeout;

        HeldExchange(HttpServerExchange exchange) {
            this.exchange = exchange;
        }

        /**
         * The handler that held the exchange returned: what the gate decided meanwhile runs now, or the hold timeout
         * starts.
         */
        void ready() {
            Runnable action;
            synchronized (this) {
                ready = true;
                action = pending;
                pending = null;
            }
            if (action != null) {
                action.run();
                return;
            }
            long millis = gate.holdTimeout().toMillis();
            timeout = exchange.getIoThread().executeAfter(() -> gate.expire(this), millis, TimeUnit.MILLISECONDS);
        }

        @Override
        protected void claimed() {
            XnioExecutor.Key key = timeout;
            if (key != null) {
                key.remove();
            }
        }

        @Override
        protected void resume(@Nullable Generation<Served> entered) {
            whenReady(() -> exchange.dispatch(exchange.getIoThread(), resumed -> {
                if (entered != null) {
                    handler.handleEntered(entered, resumed);
                } else {
                    handler.handleRequest(resumed);
                }
            }));
        }

        @Override
        protected void reject(String message) {
            whenReady(() -> exchange.dispatch(exchange.getIoThread(), rejected -> {
                rejected.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
                rejected.getResponseHeaders().put(RETRY_AFTER, DevelopmentRequestGate.RETRY_AFTER_SECONDS);
                rejected.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain;charset=utf-8");
                rejected.getResponseSender().send(message);
            }));
        }

        private void whenReady(Runnable action) {
            synchronized (this) {
                if (!ready) {
                    pending = action;
                    return;
                }
            }
            action.run();
        }
    }
}
