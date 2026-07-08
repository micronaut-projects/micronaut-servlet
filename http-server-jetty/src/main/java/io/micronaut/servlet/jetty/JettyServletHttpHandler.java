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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.servlet.engine.DefaultServletHttpHandler;
import io.micronaut.servlet.http.BodyBuilder;
import io.micronaut.servlet.http.SSLSessionProvider;
import io.micronaut.servlet.http.ServletExchange;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.Executor;

@Singleton
@Replaces(DefaultServletHttpHandler.class)
final class JettyServletHttpHandler extends DefaultServletHttpHandler {
    @Inject
    JettyServletHttpHandler(ApplicationContext applicationContext,
                            ConversionService conversionService,
                            @Named(TaskExecutors.BLOCKING) Executor ioExecutor,
                            @Nullable SSLSessionProvider sslSessionProvider) {
        super(applicationContext, conversionService, ioExecutor, sslSessionProvider);
    }

    @Override
    protected ServletExchange<HttpServletRequest, HttpServletResponse> createExchange(HttpServletRequest request,
                                                                                      HttpServletResponse response) {
        return new JettyServletHttpRequest<>(
            getApplicationContext().getConversionService(),
            request,
            response,
            getMessageBodyHandlerRegistry(),
            getApplicationContext().getBean(BodyBuilder.class),
            getIoExecutor(),
            getSslSessionProvider()
        );
    }
}
