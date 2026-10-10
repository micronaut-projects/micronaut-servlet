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

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Internal;
import io.micronaut.dev.DevRuntime;
import io.micronaut.servlet.engine.dev.LiveReloadScript;
import jakarta.inject.Singleton;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Adds the LiveReload script to the HTML pages Jetty serves itself in development mode, from the static resource
 * contexts that {@code micronaut.server.jetty.native-static-resources} mounts beside the servlet context: the servlet
 * context's pages get it from the servlet filter of the engine, these never pass through a servlet filter. The page is
 * held back until it is complete, then sent with the tag before its closing body tag, a {@code Content-Length} that
 * counts it and a {@code Cache-Control: no-store}; a response that is not a complete {@code 200} uncompressed
 * {@code text/html} page without a {@code Content-Security-Policy}, or one past
 * {@value LiveReloadScript#MAX_BUFFERED} bytes, is sent as Jetty writes it.
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Singleton
@DevelopmentActive
@Requires(classes = DevRuntime.class)
@Requires(beans = DevRuntime.class)
final class DevelopmentJettyLiveReload implements BeanCreatedEventListener<Server> {

    private final DevRuntime runtime;

    /**
     * @param runtime The development runtime
     */
    DevelopmentJettyLiveReload(DevRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Server onCreated(BeanCreatedEvent<Server> event) {
        Server server = event.getBean();
        for (ContextHandler context : server.getDescendants(ContextHandler.class)) {
            Handler handler = context.getHandler();
            if (!(context instanceof ServletContextHandler) && handler != null && !(handler instanceof Injecting)) {
                context.setHandler(new Injecting(handler));
            }
        }
        return server;
    }

    /**
     * Wraps the response of each request to a static resource context.
     */
    private final class Injecting extends Handler.Wrapper {

        Injecting(Handler handler) {
            super(handler);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            byte[] tag = LiveReloadScript.tag(runtime);
            if (tag == null || HttpMethod.HEAD.is(request.getMethod())) {
                return super.handle(request, response, callback);
            }
            InjectingResponse injecting = new InjectingResponse(request, response, tag);
            return super.handle(request, injecting, Callback.from(() -> injecting.complete(callback), callback::failed));
        }
    }

    /**
     * Holds back an HTML page until its last write, and passes anything else through.
     */
    private static final class InjectingResponse extends Response.Wrapper {
        private final byte[] tag;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private @Nullable Boolean holding;

        InjectingResponse(Request request, Response response, byte[] tag) {
            super(request, response);
            this.tag = tag;
        }

        @Override
        public void write(boolean last, @Nullable ByteBuffer content, Callback callback) {
            if (holding == null) {
                holding = holds();
            }
            if (!holding) {
                super.write(last, content, callback);
                return;
            }
            if (content != null && content.hasRemaining()) {
                byte[] bytes = new byte[content.remaining()];
                content.get(bytes);
                buffer.write(bytes, 0, bytes.length);
            }
            if (buffer.size() > LiveReloadScript.MAX_BUFFERED) {
                holding = false;
                byte[] held = buffer.toByteArray();
                buffer.reset();
                super.write(last, ByteBuffer.wrap(held), callback);
            } else if (last) {
                send(callback);
            } else {
                callback.succeeded();
            }
        }

        /**
         * Sends what is still held back once the handler completes the request without a last write.
         */
        void complete(Callback callback) {
            if (Boolean.TRUE.equals(holding)) {
                send(callback);
            } else {
                callback.succeeded();
            }
        }

        private void send(Callback callback) {
            holding = false;
            byte[] page = buffer.toByteArray();
            buffer.reset();
            byte[] injected = LiveReloadScript.inject(page, tag);
            if (injected != null && !isCommitted()) {
                page = injected;
                getHeaders().put(HttpHeader.CONTENT_LENGTH, page.length);
                getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
            }
            super.write(true, ByteBuffer.wrap(page), callback);
        }

        private boolean holds() {
            // a status not set yet is the 200 Jetty sends
            int status = getStatus();
            return (status == 0 || status == HttpStatus.OK_200)
                && !isCommitted()
                && LiveReloadScript.isHtml(getHeaders().get(HttpHeader.CONTENT_TYPE))
                && LiveReloadScript.isIdentity(getHeaders().get(HttpHeader.CONTENT_ENCODING))
                && !getHeaders().contains("Content-Security-Policy");
        }
    }
}
