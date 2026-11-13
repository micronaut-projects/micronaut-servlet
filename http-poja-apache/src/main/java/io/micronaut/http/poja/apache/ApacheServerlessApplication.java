/*
 * Copyright 2017-2024 original authors
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

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.core.io.buffer.ByteBufferFactory;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.poja.PojaHttpServerlessApplication;
import io.micronaut.http.poja.apache.exception.ApacheServletBadRequestException;
import io.micronaut.http.poja.exception.NoPojaRequestException;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.servlet.http.ServletHttpHandler;
import jakarta.inject.Singleton;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.io.SessionInputBufferImpl;
import org.apache.hc.core5.http.io.SessionInputBuffer;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of {@link PojaHttpServerlessApplication} for Apache.
 *
 * @author Andriy Dmytruk.
 * @since 4.10.0
 */
@Singleton
public class ApacheServerlessApplication
    extends PojaHttpServerlessApplication<ApacheServletHttpRequest<?>, ApacheServletHttpResponse<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(ApacheServerlessApplication.class);
    // how much already received input is discarded before a connection is closed
    private static final long CLOSE_DISCARD_LIMIT = 64 * 1024;

    private final ConversionService conversionService;
    private final MediaTypeCodecRegistry codecRegistry;
    private final ExecutorService ioExecutor;
    private final ByteBufferFactory<?, ?> byteBufferFactory;
    private final ApacheServletConfiguration configuration;
    private final Http1Config http1Config;
    private final long maxRequestSize;
    private final ScheduledThreadPoolExecutor discardScheduler;

    /**
     * Default constructor.
     *
     * @param applicationContext       The application context
     * @param applicationConfiguration The application configuration
     */
    public ApacheServerlessApplication(ApplicationContext applicationContext,
                                       ApplicationConfiguration applicationConfiguration) {
        super(applicationContext, applicationConfiguration);
        conversionService = applicationContext.getConversionService();
        codecRegistry = applicationContext.getBean(MediaTypeCodecRegistry.class);
        ioExecutor = applicationContext.getBean(ExecutorService.class, Qualifiers.byName(TaskExecutors.BLOCKING));
        configuration = applicationContext.getBean(ApacheServletConfiguration.class);
        byteBufferFactory = ByteArrayBufferFactory.INSTANCE;
        http1Config = Http1Config.custom()
            .setBufferSize(configuration.inputBufferSize())
            .setMaxLineLength(configuration.maxLineLength())
            .setMaxHeaderCount(configuration.maxHeaderCount())
            .build();
        maxRequestSize = applicationContext.findBean(HttpServerConfiguration.class)
            .map(HttpServerConfiguration::getMaxRequestSize)
            .orElse(HttpServerConfiguration.DEFAULT_MAX_REQUEST_SIZE);
        // Without core threads the watchdog thread ends when idle, so the scheduler needs no shutdown, and it is not
        // tied to stop(), which runs whenever a connection ends
        discardScheduler = new ScheduledThreadPoolExecutor(0, runnable -> {
            Thread thread = new Thread(runnable, "poja-discard-timeout");
            thread.setDaemon(true);
            return thread;
        });
        discardScheduler.setKeepAliveTime(1, TimeUnit.SECONDS);
        discardScheduler.setRemoveOnCancelPolicy(true);
    }

    @Override
    protected void runIndefinitely(
            ServletHttpHandler<ApacheServletHttpRequest<?>, ApacheServletHttpResponse<?>> servletHttpHandler,
            InputStream in,
            OutputStream out
    ) throws IOException {
        // the parser buffer belongs to the connection, since it may hold the start of the connection's next request
        serve(servletHttpHandler, in, out, true);
    }

    @Override
    protected boolean handleSingleRequest(
            ServletHttpHandler<ApacheServletHttpRequest<?>, ApacheServletHttpResponse<?>> servletHttpHandler,
            InputStream in,
            OutputStream out
    ) throws IOException {
        // requests the client pipelined into this call's buffer are served here, since the buffer does not outlive
        // the call
        return serve(servletHttpHandler, in, out, false);
    }

    /**
     * Serve requests from the connection with one parser buffer.
     *
     * @param servletHttpHandler The handler
     * @param in The input
     * @param out The output
     * @param wholeConnection Whether to serve the connection until it closes, rather than only the requests already
     *                        read into the buffer
     * @return Whether the connection can be reused
     * @throws IOException If the input cannot be read
     */
    private boolean serve(
            ServletHttpHandler<ApacheServletHttpRequest<?>, ApacheServletHttpResponse<?>> servletHttpHandler,
            InputStream in,
            OutputStream out,
            boolean wholeConnection
    ) throws IOException {
        SessionInputBufferImpl inputBuffer = createInputBuffer();
        boolean keepAlive;
        do {
            keepAlive = handleRequest(servletHttpHandler, in, out, inputBuffer);
        } while (keepAlive && (wholeConnection || inputBuffer.hasBufferedData()));
        return keepAlive;
    }

    private SessionInputBufferImpl createInputBuffer() {
        return new SessionInputBufferImpl(configuration.inputBufferSize(), http1Config.getMaxLineLength());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean handleRequest(
            ServletHttpHandler<ApacheServletHttpRequest<?>, ApacheServletHttpResponse<?>> servletHttpHandler,
            InputStream in,
            OutputStream out,
            SessionInputBuffer inputBuffer
    ) throws IOException {
        try (ApacheResponseContext responseContext = new ApacheResponseContext(configuration, out)) {
            ApacheServletHttpRequest exchange;
            try {
                exchange = new ApacheServletHttpRequest<>(
                    in, responseContext, inputBuffer, conversionService, codecRegistry, ioExecutor,
                    byteBufferFactory, http1Config, maxRequestSize
                );
            } catch (NoPojaRequestException e) {
                throw e;
            } catch (Exception e) {
                // The request could not be framed or parsed, so where the next one starts is unknown: answer it
                // and close the connection, rather than failing the serving loop
                LOG.debug("Rejecting a request that could not be parsed", e);
                if (e instanceof ApacheServletBadRequestException badRequest) {
                    rejectAndClose(responseContext, in, badRequest.getStatus(), badRequest.getMessage());
                } else {
                    rejectAndClose(responseContext, in, HttpStatus.BAD_REQUEST, "HTTP request could not be parsed");
                }
                return false;
            }
            try {
                servletHttpHandler.service(exchange);
                if (!responseContext.isCommitted()) {
                    Objects.requireNonNull(responseContext.primaryResponse, "primaryResponse").getOutputStream(); // this causes the commit
                }
                // Send the response before reading on, since a client may wait for it before sending the rest of
                // the body
                responseContext.finish();
            } catch (Exception e) {
                failed(responseContext, exchange.detachBody() ? in : null, e);
                return false;
            } finally {
                exchange.close();
            }
            // If the connection stays open, whatever the application left unread of the body is consumed, so that
            // the next request is parsed from where this one ends; if it cannot be, the connection is closed.
            if (!responseContext.connectionClose && !discardBody(exchange, in)) {
                responseContext.connectionClose = true;
            }
            // an application thread still reading the body owns the connection input, so it is not read here then
            if (responseContext.connectionClose && exchange.detachBody()) {
                discardAvailable(in);
            }
            return !responseContext.connectionClose;
        }
    }

    private void failed(ApacheResponseContext responseContext, @Nullable InputStream discardFrom, Exception e) {
        if (isDisconnect(e)) {
            LOG.debug("The response could not be written, the client has likely disconnected", e);
        } else {
            LOG.error("Unexpected error handling a request", e);
        }
        if (responseContext.isCommitted()) {
            // the status has been sent: close without completing the response, so the client sees it truncated
            responseContext.abort();
        } else {
            rejectAndClose(responseContext, discardFrom, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.getReason());
        }
    }

    /**
     * Whether a failure is the connection going away, rather than the application failing. Only the socket failures
     * a write to a closed connection produces count, so an application failure that wraps another
     * {@link IOException} is still reported as an error.
     *
     * @param e The failure
     * @return Whether the client has disconnected
     */
    static boolean isDisconnect(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketException || cause instanceof ClosedChannelException) {
                return true;
            }
            if (cause instanceof IOException && cause.getMessage() != null) {
                String message = cause.getMessage();
                if (message.contains("Broken pipe") || message.contains("Connection reset")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Consume the rest of a body the application left unread, waiting at most {@code poja.apache.discard-timeout}
     * for the client to send it. When the time is up the connection input is closed, which ends a blocked read.
     *
     * @param exchange The request
     * @param in The connection input
     * @return Whether the body was consumed and the connection can be reused
     */
    private boolean discardBody(ApacheServletHttpRequest<?> exchange, InputStream in) {
        if (!exchange.hasUnreadBody()) {
            return exchange.discardBody();
        }
        if (in == System.in) {
            // Closing standard input does not end a read blocked on it, so the timeout could not be enforced: the
            // connection is closed instead of waiting for the rest of the body.
            return false;
        }
        // whichever of the discard and the watchdog finishes first owns the connection: either the discard keeps it,
        // or the watchdog closes it, never both
        AtomicBoolean settled = new AtomicBoolean();
        ScheduledFuture<?> watchdog = discardScheduler.schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                closeQuietly(in);
            }
        }, configuration.discardTimeout().toNanos(), TimeUnit.NANOSECONDS);
        boolean discarded = exchange.discardBody();
        if (!settled.compareAndSet(false, true)) {
            // the watchdog closed the input
            return false;
        }
        watchdog.cancel(false);
        return discarded;
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            // the connection is being closed anyway
        }
    }

    private void rejectAndClose(ApacheResponseContext responseContext, @Nullable InputStream discardFrom, HttpStatus status, @Nullable String message) {
        responseContext.connectionClose = true;
        try {
            if (!responseContext.isCommitted()) {
                BasicClassicHttpResponse response = new BasicClassicHttpResponse(status.getCode());
                response.addHeader(HttpHeaders.CONNECTION, "close");
                try (OutputStream os = responseContext.commit(response)) {
                    os.write(Objects.toString(message, "").getBytes(StandardCharsets.UTF_8));
                }
            }
            responseContext.finish();
        } catch (IOException e) {
            LOG.debug("The rejection could not be written, the client has likely disconnected", e);
            responseContext.abort();
            return;
        }
        if (discardFrom != null) {
            discardAvailable(discardFrom);
        }
    }

    /**
     * Read and discard what the client has already sent, without blocking and within a bound, before the connection
     * is closed. Closing a socket with unread received data resets the connection, and a client may then lose the
     * response it has not read yet.
     *
     * @param in The connection input
     */
    private static void discardAvailable(InputStream in) {
        byte[] buffer = new byte[ApacheRequestBodyInputStream.DISCARD_BUFFER_SIZE];
        long remaining = CLOSE_DISCARD_LIMIT;
        try {
            int available = in.available();
            while (remaining > 0 && available > 0) {
                int read = in.read(buffer, 0, (int) Math.min(Math.min(available, buffer.length), remaining));
                if (read < 0) {
                    return;
                }
                remaining -= read;
                available = in.available();
            }
        } catch (IOException e) {
            // the connection is being closed anyway
        }
    }

    @Override
    protected boolean useInheritedChannel() {
        return configuration.useInheritedChannel();
    }
}
