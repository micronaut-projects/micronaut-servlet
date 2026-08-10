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
package io.micronaut.servlet.engine;

import io.micronaut.context.ApplicationContext;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.servlet.http.BodyBuilder;
import io.micronaut.servlet.http.SSLSessionProvider;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpHandler;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.net.ssl.SSLSession;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Default implementation of {@link ServletHttpHandler} for the Servlet API.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
public class DefaultServletHttpHandler extends ServletHttpHandler<HttpServletRequest, HttpServletResponse> {

    private final Executor ioExecutor;
    private final @Nullable SSLSessionProvider sslSessionProvider;

    /**
     * Default constructor.
     *
     * @param applicationContext   The application context
     * @param conversionService    The conversion service
     * @param ioExecutor           Executor to use for blocking IO operations
     * @param sslSessionProvider   The {@link SSLSession} provider from attribute
     */
    @Inject
    public DefaultServletHttpHandler(ApplicationContext applicationContext, ConversionService conversionService, @Named(TaskExecutors.BLOCKING) Executor ioExecutor, @Nullable SSLSessionProvider sslSessionProvider) {
        super(applicationContext, conversionService);
        this.ioExecutor = ioExecutor;
        this.sslSessionProvider = sslSessionProvider;
    }

    /**
     * Default constructor.
     *
     * @param applicationContext The application context
     * @param conversionService  The conversion service
     * @param ioExecutor         Executor to use for blocking IO operations
     */
    @Deprecated
    public DefaultServletHttpHandler(ApplicationContext applicationContext, ConversionService conversionService, @Named(TaskExecutors.BLOCKING) Executor ioExecutor) {
        this(applicationContext, conversionService, ioExecutor, null);
    }

    /**
     * Default constructor.
     *
     * @param applicationContext The application context
     * @param conversionService  The conversion service
     * @deprecated use {@link #DefaultServletHttpHandler(ApplicationContext, ConversionService, Executor)}
     */
    @Deprecated
    public DefaultServletHttpHandler(ApplicationContext applicationContext, ConversionService conversionService) {
        this(applicationContext, conversionService, ForkJoinPool.commonPool());
    }

    /**
     * Default constructor.
     *
     * @param applicationContext The application context
     * @deprecated use {@link #DefaultServletHttpHandler(ApplicationContext, ConversionService, Executor)}
     */
    @Deprecated
    public DefaultServletHttpHandler(ApplicationContext applicationContext) {
        this(applicationContext, ConversionService.SHARED);
    }

    @Override
    protected ServletExchange<HttpServletRequest, HttpServletResponse> createExchange(
            HttpServletRequest request,
            HttpServletResponse response) {
        return new DefaultServletHttpRequest<>(applicationContext.getConversionService(), request, response, getMessageBodyHandlerRegistry(), applicationContext.getBean(BodyBuilder.class), ioExecutor, sslSessionProvider);
    }

    @Override
    public void service(HttpServletRequest request, HttpServletResponse response) {
        final ServletExchange<HttpServletRequest, HttpServletResponse> exchange = createExchange(request, response);
        service(exchange);
    }

    @Override
    public boolean isRunning() {
        return getApplicationContext().isRunning();
    }
}
