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
package io.micronaut.http.poja;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.poja.exception.NoPojaRequestException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channel;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * A base class for POJA serverless applications.
 * It implements {@link EmbeddedApplication} for POSIX serverless environments.
 *
 * @param <REQ> The request type
 * @param <RES> The response type
 * @author Andriy Dmytruk.
 * @since 4.10.0
 */
public abstract class PojaHttpServerlessApplication<REQ, RES> implements EmbeddedApplication<PojaHttpServerlessApplication<REQ, RES>> {

    private final ApplicationContext applicationContext;
    private final ApplicationConfiguration applicationConfiguration;

    /**
     * Default constructor.
     *
     * @param applicationContext       The application context
     * @param applicationConfiguration The application configuration
     */
    public PojaHttpServerlessApplication(ApplicationContext applicationContext,
                                         ApplicationConfiguration applicationConfiguration) {
        this.applicationContext = applicationContext;
        this.applicationConfiguration = applicationConfiguration;
    }

    @Override
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    @Override
    public ApplicationConfiguration getApplicationConfiguration() {
        return applicationConfiguration;
    }

    @Override
    public boolean isRunning() {
        return true; // once this bean is instantiated, we assume it's running, so return true.
    }

    /**
     * Run the application using a particular channel.
     *
     * @param input The input stream
     * @param output The output stream
     * @return The application
     */
    public @NonNull PojaHttpServerlessApplication<REQ, RES> start(InputStream input, OutputStream output) {
        final ServletHttpHandler<REQ, RES> servletHttpHandler =
            new ServletHttpHandler<>(applicationContext, null) {
                @Override
                protected ServletExchange<REQ, RES> createExchange(Object request, Object response) {
                    throw new UnsupportedOperationException("Not expected in serverless mode.");
                }
            };
        try {
            runIndefinitely(servletHttpHandler, input, output);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (NoPojaRequestException e) {
            this.stop();
            Thread.currentThread().interrupt();
        }
        return this;
    }

    @Override
    public @NonNull PojaHttpServerlessApplication<REQ, RES> start() {
        try {
            // Default streams to streams based on System.inheritedChannel.
            // If not possible, use System.in/out.
            Channel channel = null;
            if (useInheritedChannel()) {
                channel = System.inheritedChannel();
            }
            if (channel != null) {
                try (InputStream in = Channels.newInputStream((ReadableByteChannel) channel);
                     OutputStream out = Channels.newOutputStream((WritableByteChannel) channel)) {
                    return start(in, out);
                }
            } else {
                return start(System.in, System.out);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A method to start the application in a loop.
     *
     * @param servletHttpHandler The handler
     * @param in The input stream
     * @param out The output stream
     * @throws IOException IO exception
     */
    @SuppressWarnings({"InfiniteLoopStatement", "java:S2189"})
    protected void runIndefinitely(
            ServletHttpHandler<REQ, RES> servletHttpHandler,
            InputStream in,
            OutputStream out
    ) throws IOException {
        while (true) {
            handleSingleRequest(servletHttpHandler, in, out);
        }
    }

    /**
     * Handle a single request.
     *
     * @param servletHttpHandler The handler
     * @param in The input stream
     * @param out The output stream
     * @throws IOException IO exception
     */
    protected abstract void handleSingleRequest(
            ServletHttpHandler<REQ, RES> servletHttpHandler,
            InputStream in,
            OutputStream out
    ) throws IOException;

    /**
     * Whether to use the inherited channel by default.
     * If false, STDIN and STDOUT will be used directly instead.
     *
     * @return Whether to use the inherited channel
     */
    protected boolean useInheritedChannel() {
        return true;
    }

    @Override
    public @NonNull PojaHttpServerlessApplication<REQ, RES> stop() {
        return EmbeddedApplication.super.stop();
    }

}
