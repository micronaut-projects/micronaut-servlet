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
package io.micronaut.servlet.tomcat;

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
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.mapper.MappingData;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.util.SessionConfig;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.UpgradeProtocol;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.ServerCookie;
import org.apache.tomcat.util.http.ServerCookies;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Tomcat server kept across the restarts of the application in development mode, with its service, its connectors,
 * so that the ports stay bound, and its executors. It is built once, by the first generation that uses it, and holds
 * nothing of a generation but the one it serves: each generation deploys its own {@link Context}, with its servlets,
 * filters, listeners, mappings, access log and session manager, in the kept host, in place of the stopping generation's.
 *
 * <p>A valve of the kept engine is the gate of every request, a {@link DevelopmentRequestGate}: while the development
 * launcher's {@link RequestAdmission} says a batch of changes compiles or applies, and while one generation's context is
 * removed and the next one's deployed, a request to any mapping, Micronaut's or not, waits, on its thread, for
 * {@link RequestAdmission#holdTimeout()} at most, after which it is answered with a 503 and a {@code Retry-After}; a
 * waiting request is mapped again to the context of the generation that serves it. The requests in flight on the
 * stopping generation may finish on it, for {@link RequestAdmission#drainTimeout()} at most, before its context stops.</p>
 *
 * <p>Retained across restarts until a change under {@value HttpServerConfiguration#PREFIX}, which configures the
 * server, its connectors, Tomcat and the access log, {@value MicronautServletConfiguration#PREFIX}, which configures the
 * thread pool, or {@value SslConfiguration#PREFIX}: the next generation then builds its own server, and this one is
 * stopped with the stopped context. A generation whose connectors differ, because its routes expose other ports, does
 * not use it either. The server starts with the loader of Tomcat's integration as the context class loader, since its
 * acceptor and poller threads keep the one they start with.</p>
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@Retain(invalidatedBy = {HttpServerConfiguration.PREFIX, MicronautServletConfiguration.PREFIX, SslConfiguration.PREFIX})
@DevelopmentActive
final class RetainedTomcatServer {

    private static final Logger LOG = LoggerFactory.getLogger(RetainedTomcatServer.class);
    /**
     * The note of a request the gate admitted: an asynchronous dispatch of it passes through.
     */
    private static final String ADMITTED_NOTE = RetainedTomcatServer.class.getName() + ".admitted";

    private final Object lock = new Object();
    private final DevelopmentRequestGate<List<Context>> gate = new DevelopmentRequestGate<>();
    private @Nullable Tomcat server;
    private @Nullable String connectors;
    /**
     * The contexts of the generation that uses the server, until it starts; none for the first, whose contexts are the
     * kept host's already.
     */
    private @Nullable List<Context> staged;

    /**
     * Created once, as the first generation starts, and kept from then on.
     */
    RetainedTomcatServer() {
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
     * contexts staged for it; otherwise the generation's own, kept from now on in place of the one kept so far.
     *
     * @param built The server the generation built, with its connectors and its contexts, not started
     * @return The server to run
     */
    Tomcat serve(Tomcat built) {
        String signature = signature(built);
        Tomcat released;
        synchronized (lock) {
            Tomcat kept = server;
            if (kept != null && kept.getServer().getState().isAvailable() && signature.equals(connectors)) {
                // the contexts move to the kept host as the generation starts; the generation's server, never started,
                // goes with the generation
                staged = contexts(built.getHost());
                LOG.debug("Tomcat server on {} retained across the restart", signature);
                return kept;
            }
            if (kept != null) {
                LOG.debug("The Tomcat server listens on {} rather than {}: the one kept is stopped", signature, connectors);
            }
            released = kept;
            staged = null;
            built.getEngine().getPipeline().addValve(new GateValve());
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
    boolean holds(Tomcat candidate) {
        synchronized (lock) {
            return server == candidate;
        }
    }

    /**
     * Starts the server, unless it runs already, then deploys the contexts the generation staged, which serve its
     * requests and those held for it from then on.
     *
     * @return The generation
     * @throws LifecycleException If the server or a context cannot start
     */
    Generation<List<Context>> start() throws LifecycleException {
        Tomcat kept;
        List<Context> contexts;
        synchronized (lock) {
            kept = server;
            contexts = staged;
            staged = null;
        }
        if (kept == null) {
            throw new IllegalStateException("No generation uses the retained Tomcat server");
        }
        List<Context> deployed = DevelopmentRequestGate.withLoaderOf(RetainedTomcatServer.class, () -> {
            if (contexts == null) {
                kept.start();
                return contexts(kept.getHost());
            }
            Host host = kept.getHost();
            try {
                for (Context context : contexts) {
                    // starts it, and maps it once started
                    host.addChild(context);
                    if (!context.getState().isAvailable()) {
                        throw new LifecycleException("The context " + context.getName() + " of the generation did not start");
                    }
                }
            } catch (LifecycleException | RuntimeException e) {
                // a context that failed to start stays in the host, where the next generation's would clash with it
                for (Context context : contexts) {
                    if (context.getParent() == host) {
                        host.removeChild(context);
                    }
                }
                throw e;
            }
            return contexts;
        });
        return gate.start(deployed);
    }

    /**
     * The generation stops serving: the requests that arrive from now on wait for the next one.
     *
     * @param generation The generation
     * @return A future that completes once no request of the generation is in flight
     */
    CompletableFuture<Void> retire(Generation<List<Context>> generation) {
        return gate.retire(generation);
    }

    /**
     * Removes the contexts of a generation from the kept host, once the requests in flight on it finished, or after the
     * drain timeout: they stop, which closes their WebSocket sessions going away, and are destroyed.
     *
     * @param generation The generation
     */
    void stop(Generation<List<Context>> generation) {
        gate.drain(generation);
        undeploy(generation);
    }

    /**
     * Stops the server: a change released it, or the development runtime closes.
     */
    @PreDestroy
    void close() {
        Tomcat released;
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
        Generation<List<Context>> running = gate.close("The application stopped");
        if (running != null) {
            undeploy(running);
        }
    }

    private static void undeploy(Generation<List<Context>> generation) {
        if (!generation.stop()) {
            return;
        }
        DevelopmentRequestGate.withLoaderOf(RetainedTomcatServer.class, () -> {
            for (Context context : generation.served()) {
                Container host = context.getParent();
                if (host != null) {
                    host.removeChild(context);
                }
            }
            return null;
        });
    }

    private static void stop(@Nullable Tomcat released) {
        if (released == null) {
            return;
        }
        try {
            DevelopmentRequestGate.withLoaderOf(RetainedTomcatServer.class, () -> {
                released.stop();
                released.destroy();
                return null;
            });
        } catch (LifecycleException e) {
            LOG.debug("Cannot stop the released Tomcat server", e);
        }
    }

    private static List<Context> contexts(Host host) {
        List<Context> contexts = new ArrayList<>();
        for (Container child : host.findChildren()) {
            if (child instanceof Context context) {
                contexts.add(context);
            }
        }
        return contexts;
    }

    /**
     * What a server listens on: a generation that listens on the same addresses, with the same protocols, can use the
     * kept one.
     */
    private static String signature(Tomcat server) {
        StringBuilder signature = new StringBuilder();
        for (Connector connector : server.getService().findConnectors()) {
            signature.append('[').append(connector.getProtocolHandlerClassName());
            if (connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol) {
                signature.append(' ').append(protocol.getAddress());
            }
            signature.append(':').append(connector.getPort())
                .append(' ').append(connector.getScheme())
                .append(' ').append(connector.getSecure())
                .append(' ').append(connector.findSslHostConfigs().length);
            for (UpgradeProtocol upgrade : connector.findUpgradeProtocols()) {
                signature.append(' ').append(upgrade.getClass().getName());
            }
            signature.append(']');
        }
        return signature.toString();
    }

    /**
     * Maps a request again, to the contexts of the generation that serves it: it was mapped as it arrived, maybe to the
     * context of a generation that stopped meanwhile, or to none while none was deployed.
     */
    private static void remap(Request request, List<Context> contexts) throws IOException {
        Context mapped = request.getContext();
        if (mapped != null && contexts.contains(mapped)) {
            return;
        }
        MappingData mapping = request.getMappingData();
        mapping.recycle();
        org.apache.coyote.Request coyote = request.getCoyoteRequest();
        Connector connector = request.getConnector();
        MessageBytes host = connector.getUseIPVHosts() ? coyote.localName() : coyote.serverName();
        connector.getService().getMapper().map(host, coyote.decodedURI(), null, mapping);
        Context context = request.getContext();
        if (context == null || request.getRequestedSessionId() != null) {
            return;
        }
        // the session the request asks for, which is parsed as it arrives only when a context was mapped
        String fromUri = request.getPathParameter(SessionConfig.getSessionUriParamName(context));
        if (fromUri != null) {
            request.setRequestedSessionId(fromUri);
            request.setRequestedSessionURL(true);
            return;
        }
        if (context.getCookies()) {
            String name = SessionConfig.getSessionCookieName(context);
            ServerCookies cookies = request.getServerCookies();
            for (int i = 0; i < cookies.getCookieCount(); i++) {
                ServerCookie cookie = cookies.getCookie(i);
                if (cookie.getName().equals(name)) {
                    request.setRequestedSessionId(cookie.getValue().toString());
                    request.setRequestedSessionCookie(true);
                    request.setRequestedSessionURL(false);
                    return;
                }
            }
        }
    }

    /**
     * The valve of the kept engine: hands each request to the context of the running generation, or holds it on its
     * thread until it may proceed.
     */
    private final class GateValve extends ValveBase {

        GateValve() {
            // requests may be asynchronous: an engine valve that is not would turn that off for every one
            super(true);
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            if (request.getNote(ADMITTED_NOTE) != null) {
                // an asynchronous dispatch of a request the gate admitted, and counts, already
                getNext().invoke(request, response);
                return;
            }
            Generation<List<Context>> running = null;
            while (running == null) {
                BlockedRequest[] waiting = new BlockedRequest[1];
                running = gate.admit(() -> waiting[0] = new BlockedRequest());
                if (running == null) {
                    Outcome outcome = waiting[0].await();
                    if (outcome.message() != null) {
                        unavailable(response, outcome.message());
                        return;
                    }
                    // entered as admitted, or to go through the gate again
                    running = outcome.entered();
                }
            }
            serve(running, request, response);
        }

        private void serve(Generation<List<Context>> running, Request request, Response response) throws IOException, ServletException {
            request.setNote(ADMITTED_NOTE, Boolean.TRUE);
            try {
                remap(request, running.served());
                getNext().invoke(request, response);
            } finally {
                boolean counted = false;
                if (request.isAsync()) {
                    try {
                        request.getAsyncContextInternal().addListener(new Exit(running));
                        counted = true;
                    } catch (RuntimeException e) {
                        // completed meanwhile
                    }
                }
                if (!counted) {
                    running.exit();
                }
            }
        }

        private static void unavailable(Response response, String message) throws IOException {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            response.setStatus(503);
            response.setHeader("Retry-After", DevelopmentRequestGate.RETRY_AFTER_SECONDS);
            response.setContentType("text/plain;charset=utf-8");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        }
    }

    /**
     * What a held request is resumed with: the generation that counted it, if any, or a 503.
     *
     * @param entered The generation, if any
     * @param message The body of the 503, if any
     */
    private record Outcome(@Nullable Generation<List<Context>> entered, @Nullable String message) {
    }

    /**
     * A request held on its thread, which waits until the gate resumes it, or answers it with a 503 past the hold.
     */
    private final class BlockedRequest extends DevelopmentRequestGate.HeldRequest<List<Context>> {
        private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();

        Outcome await() {
            try {
                return outcome.get(gate.holdTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                gate.expire(this);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                gate.expire(this);
            } catch (ExecutionException e) {
                gate.expire(this);
            }
            // answered by the gate, or resumed just before
            return outcome.join();
        }

        @Override
        protected void resume(@Nullable Generation<List<Context>> entered) {
            outcome.complete(new Outcome(entered, null));
        }

        @Override
        protected void reject(String message) {
            outcome.complete(new Outcome(null, message));
        }
    }

    /**
     * Counts an asynchronous request as finished once it completes.
     */
    private static final class Exit implements AsyncListener {
        private final Generation<List<Context>> generation;
        private final AtomicBoolean exited = new AtomicBoolean();

        Exit(Generation<List<Context>> generation) {
            this.generation = generation;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            if (exited.compareAndSet(false, true)) {
                generation.exit();
            }
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            // completes next
        }

        @Override
        public void onError(AsyncEvent event) {
            // completes next
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // a new asynchronous cycle drops the listeners of the previous one
            event.getAsyncContext().addListener(this);
        }
    }
}
