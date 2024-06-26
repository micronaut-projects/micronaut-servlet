/*
 * Copyright © 2024 Oracle and/or its affiliates.
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
package io.micronaut.http.poja.rawhttp;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpHandler;
import jakarta.inject.Singleton;
import rawhttp.core.RawHttpRequest;
import rawhttp.core.RawHttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channel;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.ExecutorService;

/**
 * Implementation of {@link EmbeddedApplication} for POSIX serverless environments.
 *
 * @author Sahoo.
 */
@Singleton
public class ServerlessApplication implements EmbeddedApplication<ServerlessApplication> {

    private final ApplicationContext applicationContext;
    private final ApplicationConfiguration applicationConfiguration;

    /**
     * Default constructor.
     *
     * @param applicationContext       The application context
     * @param applicationConfiguration The application configuration
     */
    public ServerlessApplication(ApplicationContext applicationContext,
                                 ApplicationConfiguration applicationConfiguration) {
        // TODO: Accept InputStream and OutputStream so that they can be configured using beans.
        // default them to streams based on System.inheritedChannel if possible, else System.in/out.
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
     * Run the application using a particular channel
     *
     * @param input The input stream
     * @param output The output stream
     * @return The application
     */
    protected @NonNull ServerlessApplication start(InputStream input, OutputStream output) {
        final ServletHttpHandler<RawHttpRequest, RawHttpResponse<Void>> servletHttpHandler =
            new ServletHttpHandler<>(applicationContext, null) {
                @Override
                protected ServletExchange<RawHttpRequest, RawHttpResponse<Void>> createExchange(RawHttpRequest request,
                                                                                                RawHttpResponse response) {
                    throw new UnsupportedOperationException("Not expected in serverless mode.");
                }
            };
        try {
            runIndefinitely(servletHttpHandler, applicationContext, input, output);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    @Override
    public @NonNull ServerlessApplication start() {
        try {
            Channel channel = System.inheritedChannel();
            if (channel != null) {
                try (InputStream in = Channels.newInputStream((ReadableByteChannel) channel);
                     OutputStream out = Channels.newOutputStream((WritableByteChannel) channel)) {
                    return start(in, out);
                }
            } else {
                return start(System.in, System.out);
            }
        } catch (IOException e) {
            throw new RuntimeException();
        }
    }

    void runIndefinitely(ServletHttpHandler<RawHttpRequest, RawHttpResponse<Void>> servletHttpHandler,
                         ApplicationContext applicationContext,
                         InputStream in,
                         OutputStream out) throws IOException {
        while (true) {
            handleSingleRequest(servletHttpHandler, applicationContext, in, out);
        }
    }

    void handleSingleRequest(ServletHttpHandler<RawHttpRequest, RawHttpResponse<Void>> servletHttpHandler,
                                    ApplicationContext applicationContext,
                                    InputStream in,
                                    OutputStream out) throws IOException {
        ConversionService conversionService = applicationContext.getConversionService();
        MediaTypeCodecRegistry codecRegistry = applicationContext.getBean(MediaTypeCodecRegistry.class);
        ExecutorService ioExecutor = applicationContext.getBean(ExecutorService.class, Qualifiers.byName(TaskExecutors.BLOCKING));

        RawHttpBasedServletHttpResponse<Void> response = new RawHttpBasedServletHttpResponse<>(conversionService);
        RawHttpBasedServletHttpRequest<?> exchange = new RawHttpBasedServletHttpRequest<>(
            in, conversionService, codecRegistry, ioExecutor, response
        );

        servletHttpHandler.service(exchange);
        RawHttpResponse<?> rawHttpResponse = response.getNativeResponse();
        rawHttpResponse.writeTo(out);
    }

    @Override
    public @NonNull ServerlessApplication stop() {
        return EmbeddedApplication.super.stop();
    }

}
