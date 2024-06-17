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
package io.micronaut.http.poja;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.DefaultMutableConversionService;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpHandler;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.ServletHttpResponse;
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

    @Override
    public @NonNull ServerlessApplication start() {
        final ConversionService conversionService = new DefaultMutableConversionService();
        final ServletHttpHandler<RawHttpRequest, RawHttpResponse<Void>> servletHttpHandler =
            new ServletHttpHandler<>(applicationContext, null) {
                @Override
                protected ServletExchange<RawHttpRequest, RawHttpResponse<Void>> createExchange(RawHttpRequest request,
                                                                                                RawHttpResponse response) {
                    throw new UnsupportedOperationException("Not expected in serverless mode.");
                }
            };
        try {
            Channel channel = System.inheritedChannel();
            if (channel != null) {
                try (InputStream in = Channels.newInputStream((ReadableByteChannel) channel);
                     OutputStream out = Channels.newOutputStream((WritableByteChannel) channel)) {
                    runIndefinitely(servletHttpHandler, conversionService, in, out);
                }
            } else {
                runIndefinitely(servletHttpHandler, conversionService, System.in, System.out);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    void runIndefinitely(ServletHttpHandler<RawHttpRequest, RawHttpResponse<Void>> servletHttpHandler,
                         ConversionService conversionService,
                         InputStream in,
                         OutputStream out) throws IOException {
        while (true) {
            handleSingleRequest(servletHttpHandler, conversionService, in, out);
        }
    }

    void handleSingleRequest(ServletHttpHandler<RawHttpRequest, RawHttpResponse<Void>> servletHttpHandler,
                                    ConversionService conversionService,
                                    InputStream in,
                                    OutputStream out) throws IOException {
        ServletExchange<RawHttpRequest, RawHttpResponse<Void>> servletExchange =
            new ServletExchange<>() {
                private final ServletHttpRequest<RawHttpRequest, Object> httpRequest =
                    new RawHttpBasedServletHttpRequest(in, conversionService);

                private final ServletHttpResponse<RawHttpResponse<Void>, String> httpResponse =
                    new RawHttpBasedServletHttpResponse(conversionService);

                @Override
                public ServletHttpRequest<RawHttpRequest, Object> getRequest() {
                    return httpRequest;
                }

                @Override
                public ServletHttpResponse<RawHttpResponse<Void>, String> getResponse() {
                    return httpResponse;
                }
            };

        servletHttpHandler.service(servletExchange);
        RawHttpResponse<Void> rawHttpResponse = servletExchange.getResponse().getNativeResponse();
        rawHttpResponse.writeTo(out);
    }

}
