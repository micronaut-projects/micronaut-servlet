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
package io.micronaut.servlet.engine.dev;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.core.annotation.Internal;
import io.micronaut.dev.DevRuntime;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Adds the LiveReload client script to the HTML pages of the servlet container in development mode, as
 * {@code micronaut-dev}'s {@code LiveReloadScriptFilter} does for the responses of Micronaut's own routes: a page a
 * servlet or a filter that is not Micronaut's writes to the response gets the same script tag before its closing body
 * tag, a {@code Cache-Control: no-store} and a {@code Content-Length} that counts the tag.
 *
 * <p>The response of every request is wrapped; it holds the body back only once the first bytes are written to a
 * {@code 200} uncompressed {@code text/html} response without a {@code Content-Security-Policy}, a
 * {@code Transfer-Encoding} or a write listener, and sends it as soon as the request completes, the page grows past
 * {@value LiveReloadScript#MAX_BUFFERED} bytes, or is flushed. A page flushed before its closing body tag is written
 * was streamed: it is sent as written, without the script. A page that carries the tag already, because Micronaut's
 * filter added it, is sent as it is. A response that is not HTML is passed through untouched, its writer and stream the
 * container's own.</p>
 *
 * <p>A response whose status or headers change so that it no longer qualifies is sent as written. So is the page of an
 * asynchronous request the application dispatches, or the container completes on a timeout or an error, rather than
 * completing it through its context.</p>
 *
 * <p>The filter exists only in development mode, when the development launcher runs the application, and does nothing
 * when it runs no LiveReload server or the manifest turns the injection off.</p>
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@WebFilter(filterName = DevelopmentLiveReloadFilter.NAME, urlPatterns = "/*", asyncSupported = true)
@DevelopmentActive
@Requires(classes = DevRuntime.class)
@Requires(beans = DevRuntime.class)
public final class DevelopmentLiveReloadFilter implements Filter {

    /**
     * The name of the filter.
     */
    static final String NAME = "micronautDevLiveReload";
    private static final Logger LOG = LoggerFactory.getLogger(DevelopmentLiveReloadFilter.class);
    private static final String APPLIED = DevelopmentLiveReloadFilter.class.getName() + ".applied";
    private static final String CONTENT_LENGTH = "Content-Length";

    private final DevRuntime runtime;

    /**
     * @param runtime The development runtime
     */
    DevelopmentLiveReloadFilter(DevRuntime runtime) {
        this.runtime = runtime;
    }

    private static boolean isWebSocketUpgrade(HttpServletRequest request) {
        // an h2c upgrade, which clients such as the JDK's offer on any request, is the connector's and leaves the
        // response as it is
        String upgrade = request.getHeader("Upgrade");
        return upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket");
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        byte[] tag = LiveReloadScript.tag(runtime);
        if (tag == null
            || !(req instanceof HttpServletRequest request)
            || !(res instanceof HttpServletResponse response)
            || req.getAttribute(APPLIED) != null
            || "HEAD".equalsIgnoreCase(request.getMethod())
            || isWebSocketUpgrade(request)) {
            chain.doFilter(req, res);
            return;
        }
        request.setAttribute(APPLIED, Boolean.TRUE);
        InjectingResponse injecting = new InjectingResponse(response, tag);
        InjectingRequest wrapped = new InjectingRequest(request, injecting);
        chain.doFilter(wrapped, injecting);
        if (!wrapped.async) {
            injecting.finish();
        }
    }

    /**
     * The request, which hands the asynchronous context it starts a hook that sends the page before it completes.
     */
    private static final class InjectingRequest extends HttpServletRequestWrapper {
        private final InjectingResponse response;
        private volatile boolean async;
        private volatile @Nullable AsyncContext context;

        InjectingRequest(HttpServletRequest request, InjectingResponse response) {
            super(request);
            this.response = response;
        }

        @Override
        public AsyncContext startAsync() {
            return startAsync(this, response);
        }

        @Override
        public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
            AsyncContext started = super.startAsync(servletRequest, servletResponse);
            async = true;
            // a request that times out or fails is completed by the container, not through this context
            started.addListener(new ReleasingListener(response));
            AsyncContext finishing = new FinishingAsyncContext(started, response);
            context = finishing;
            return finishing;
        }

        @Override
        public AsyncContext getAsyncContext() {
            AsyncContext finishing = context;
            return finishing != null ? finishing : super.getAsyncContext();
        }
    }

    /**
     * Sends the page held back as it was written when the container completes an asynchronous request itself, on a
     * timeout or an error, so that it does not go missing.
     *
     * @param response The response that holds the page back
     */
    private record ReleasingListener(InjectingResponse response) implements AsyncListener {

        @Override
        public void onComplete(AsyncEvent event) {
            // completed through the context, which sent the page
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            response.release();
        }

        @Override
        public void onError(AsyncEvent event) {
            response.release();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // a new asynchronous cycle drops the listeners of the previous one
            event.getAsyncContext().addListener(this);
        }
    }

    /**
     * Sends the page held back before the asynchronous request completes.
     *
     * @param delegate The context the container started
     * @param response The response that holds the page back
     */
    private record FinishingAsyncContext(AsyncContext delegate, InjectingResponse response) implements AsyncContext {

        @Override
        public ServletRequest getRequest() {
            return delegate.getRequest();
        }

        @Override
        public ServletResponse getResponse() {
            return delegate.getResponse();
        }

        @Override
        public boolean hasOriginalRequestAndResponse() {
            return delegate.hasOriginalRequestAndResponse();
        }

        @Override
        public void dispatch() {
            // the dispatch target finishes the response, and the container completes it without this context
            response.release();
            delegate.dispatch();
        }

        @Override
        public void dispatch(String path) {
            response.release();
            delegate.dispatch(path);
        }

        @Override
        public void dispatch(ServletContext context, String path) {
            response.release();
            delegate.dispatch(context, path);
        }

        @Override
        public void complete() {
            try {
                response.finish();
            } catch (IOException | RuntimeException e) {
                LOG.debug("Cannot send the page of an asynchronous request", e);
            }
            delegate.complete();
        }

        @Override
        public void start(Runnable run) {
            delegate.start(run);
        }

        @Override
        public void addListener(AsyncListener listener) {
            delegate.addListener(listener);
        }

        @Override
        public void addListener(AsyncListener listener, ServletRequest servletRequest, ServletResponse servletResponse) {
            delegate.addListener(listener, servletRequest, servletResponse);
        }

        @Override
        public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException {
            return delegate.createListener(clazz);
        }

        @Override
        public void setTimeout(long timeout) {
            delegate.setTimeout(timeout);
        }

        @Override
        public long getTimeout() {
            return delegate.getTimeout();
        }
    }

    /**
     * Encodes what is written to it into the stream at once, so that no character waits in an encoder's buffer for a
     * flush that a container completing the response would not make; a high surrogate waits for its low one.
     */
    private static final class EncodingWriter extends Writer {
        private final ServletOutputStream stream;
        private final Charset charset;
        private char pendingHighSurrogate;

        EncodingWriter(ServletOutputStream stream, Charset charset) {
            this.stream = stream;
            this.charset = charset;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (len == 0) {
                return;
            }
            StringBuilder chars = new StringBuilder(len + 1);
            if (pendingHighSurrogate != 0) {
                chars.append(pendingHighSurrogate);
                pendingHighSurrogate = 0;
            }
            chars.append(cbuf, off, len);
            char last = chars.charAt(chars.length() - 1);
            if (Character.isHighSurrogate(last)) {
                pendingHighSurrogate = last;
                chars.setLength(chars.length() - 1);
            }
            if (!chars.isEmpty()) {
                stream.write(chars.toString().getBytes(charset));
            }
        }

        @Override
        public void flush() throws IOException {
            stream.flush();
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    /**
     * The response, which holds back an HTML page until it can add the tag, and passes anything else through.
     */
    private static final class InjectingResponse extends HttpServletResponseWrapper {
        private final byte[] tag;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Mode mode = Mode.UNDECIDED;
        /**
         * The length set while the body is held back or not decided upon, or -1.
         */
        private long contentLength = -1;
        private @Nullable HoldingStream stream;
        private @Nullable PrintWriter writer;
        private boolean finishing;

        InjectingResponse(HttpServletResponse response, byte[] tag) {
            super(response);
            this.tag = tag;
        }

        @Override
        public void setContentLength(int len) {
            setContentLengthLong(len);
        }

        @Override
        public void setContentLengthLong(long len) {
            if (mode == Mode.PASSTHROUGH) {
                super.setContentLengthLong(len);
            } else {
                contentLength = len;
            }
        }

        @Override
        public void setHeader(String name, @Nullable String value) {
            if (CONTENT_LENGTH.equalsIgnoreCase(name) && mode != Mode.PASSTHROUGH) {
                contentLength = parseLength(value);
                return;
            }
            super.setHeader(name, value);
            headerSet(name);
        }

        @Override
        public void addHeader(String name, String value) {
            if (CONTENT_LENGTH.equalsIgnoreCase(name) && mode != Mode.PASSTHROUGH) {
                contentLength = parseLength(value);
                return;
            }
            super.addHeader(name, value);
            headerSet(name);
        }

        @Override
        public void setIntHeader(String name, int value) {
            if (CONTENT_LENGTH.equalsIgnoreCase(name) && mode != Mode.PASSTHROUGH) {
                contentLength = value;
                return;
            }
            super.setIntHeader(name, value);
        }

        @Override
        public void addIntHeader(String name, int value) {
            if (CONTENT_LENGTH.equalsIgnoreCase(name) && mode != Mode.PASSTHROUGH) {
                contentLength = value;
                return;
            }
            super.addIntHeader(name, value);
        }

        @Override
        public boolean containsHeader(String name) {
            if (CONTENT_LENGTH.equalsIgnoreCase(name) && mode != Mode.PASSTHROUGH) {
                return contentLength >= 0;
            }
            return super.containsHeader(name);
        }

        @Override
        public @Nullable String getHeader(String name) {
            if (CONTENT_LENGTH.equalsIgnoreCase(name) && mode != Mode.PASSTHROUGH) {
                return contentLength >= 0 ? String.valueOf(contentLength) : null;
            }
            return super.getHeader(name);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) {
                throw new IllegalStateException("getWriter() has already been called for this response");
            }
            if (stream != null) {
                return stream;
            }
            if (mode == Mode.PASSTHROUGH || knownNotHtml()) {
                passthrough();
                return super.getOutputStream();
            }
            stream = new HoldingStream();
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer != null) {
                return writer;
            }
            if (stream != null) {
                throw new IllegalStateException("getOutputStream() has already been called for this response");
            }
            if (mode == Mode.PASSTHROUGH || knownNotHtml()) {
                passthrough();
                return super.getWriter();
            }
            // as the container's writer would, the content type declares the charset the writer encodes with
            String encoding = getCharacterEncoding();
            String charset = encoding != null ? encoding : StandardCharsets.ISO_8859_1.name();
            super.setCharacterEncoding(charset);
            HoldingStream holding = new HoldingStream();
            stream = holding;
            writer = new PrintWriter(new EncodingWriter(holding, Charset.forName(charset)));
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            PrintWriter w = writer;
            if (w != null) {
                w.flush();
            }
            HoldingStream s = stream;
            if (s != null) {
                s.flush();
            } else {
                passthrough();
                super.flushBuffer();
            }
        }

        @Override
        public void reset() {
            super.reset();
            buffer.reset();
            contentLength = -1;
            if (mode == Mode.BUFFERING) {
                mode = Mode.UNDECIDED;
            }
        }

        @Override
        public void resetBuffer() {
            super.resetBuffer();
            buffer.reset();
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            discard();
            super.sendError(sc, msg);
        }

        @Override
        public void sendError(int sc) throws IOException {
            discard();
            super.sendError(sc);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            discard();
            super.sendRedirect(location);
        }

        /**
         * Sends what is held back, with the tag if it can go in, once the request completes.
         *
         * @throws IOException If the page cannot be sent
         */
        void finish() throws IOException {
                if (finishing) {
                return;
            }
            finishing = true;
            PrintWriter w = writer;
            if (w != null) {
                // the writer's encoder hands its last bytes to the stream, which holds them while finishing
                w.flush();
            }
            if (mode == Mode.BUFFERING) {
                send(true);
            } else {
                passthrough();
            }
        }

        private void discard() {
            buffer.reset();
            contentLength = -1;
            mode = Mode.PASSTHROUGH;
        }

        private void headerSet(String name) {
            if (mode == Mode.BUFFERING && ("Content-Encoding".equalsIgnoreCase(name) || "Transfer-Encoding".equalsIgnoreCase(name))) {
                // encoded or streamed after all; a policy set late is checked as the page is sent
                release();
            }
        }

        private boolean knownNotHtml() {
            String contentType = getContentType();
            return contentType != null && !LiveReloadScript.isHtml(contentType);
        }

        /**
         * Decides, as the first bytes are written, whether the page is held back.
         */
        private void decide() throws IOException {
            if (eligible()) {
                mode = Mode.BUFFERING;
            } else {
                passthrough();
            }
        }

        /**
         * Whether the response is a complete, uncompressed HTML page the script may go into: asked as the first bytes
         * are written and again before the page is sent, since its headers or status may change meanwhile.
         */
        private boolean eligible() {
            return getStatus() == HttpServletResponse.SC_OK
                && LiveReloadScript.isHtml(getContentType())
                && LiveReloadScript.isIdentity(super.getHeader("Content-Encoding"))
                && !super.containsHeader("Content-Security-Policy")
                && !super.containsHeader("Transfer-Encoding");
        }

        /**
         * Sends what is held back as it was written, from a path that cannot throw.
         */
        void release() {
            try {
                passthrough();
            } catch (IOException | RuntimeException e) {
                LOG.debug("Cannot send the page held back", e);
            }
        }

        /**
         * Sends what is held back as it was written, with the length set meanwhile, and everything after it.
         */
        private void passthrough() throws IOException {
            if (mode == Mode.PASSTHROUGH) {
                return;
            }
            mode = Mode.PASSTHROUGH;
            if (contentLength >= 0 && !isCommitted()) {
                super.setContentLengthLong(contentLength);
            }
            if (buffer.size() > 0) {
                byte[] held = buffer.toByteArray();
                buffer.reset();
                super.getOutputStream().write(held);
            }
        }

        /**
         * Sends the page held back with the tag, if it can go in.
         *
         * @param complete Whether the page is complete, so that its length is known
         */
        private void send(boolean complete) throws IOException {
            byte[] injected = eligible() ? LiveReloadScript.inject(buffer.toByteArray(), tag) : null;
            if (injected == null) {
                passthrough();
                return;
            }
            buffer.reset();
            mode = Mode.PASSTHROUGH;
            if (!isCommitted()) {
                if (contentLength >= 0) {
                    super.setContentLengthLong(contentLength + tag.length);
                } else if (complete) {
                    super.setContentLengthLong(injected.length);
                }
                super.setHeader("Cache-Control", "no-store");
            }
            super.getOutputStream().write(injected);
        }

        private static long parseLength(@Nullable String value) {
            if (value == null) {
                return -1;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private enum Mode {
            /**
             * Nothing written yet.
             */
            UNDECIDED,
            /**
             * An HTML page held back.
             */
            BUFFERING,
            /**
             * Written through to the container.
             */
            PASSTHROUGH
        }

        /**
         * The stream of a response that may be an HTML page: holds it back, or writes through.
         */
        private final class HoldingStream extends ServletOutputStream {

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (mode == Mode.UNDECIDED) {
                    decide();
                }
                if (mode == Mode.BUFFERING) {
                    buffer.write(b, off, len);
                    if (buffer.size() > LiveReloadScript.MAX_BUFFERED) {
                        passthrough();
                    }
                    return;
                }
                InjectingResponse.super.getOutputStream().write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                if (finishing && mode != Mode.PASSTHROUGH) {
                    return;
                }
                if (mode == Mode.BUFFERING) {
                    if (LiveReloadScript.hasBodyEnd(buffer.toByteArray(), buffer.size())) {
                        send(false);
                    } else {
                        // streamed: what is written goes out as it is
                        passthrough();
                    }
                } else if (mode == Mode.UNDECIDED) {
                    passthrough();
                }
                InjectingResponse.super.getOutputStream().flush();
            }

            @Override
            public void close() throws IOException {
                finish();
                InjectingResponse.super.getOutputStream().close();
            }

            @Override
            public boolean isReady() {
                if (mode == Mode.PASSTHROUGH) {
                    try {
                        return InjectingResponse.super.getOutputStream().isReady();
                    } catch (IOException e) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                try {
                    // a response written asynchronously is streamed
                    passthrough();
                    InjectingResponse.super.getOutputStream().setWriteListener(writeListener);
                } catch (IOException e) {
                    writeListener.onError(e);
                }
            }
        }
    }
}
