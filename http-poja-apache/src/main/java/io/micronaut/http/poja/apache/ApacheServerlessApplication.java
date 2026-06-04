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
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.core.io.buffer.ByteBufferFactory;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.poja.PojaHttpServerlessApplication;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.servlet.http.ServletHttpHandler;
import jakarta.inject.Singleton;
import org.apache.hc.core5.http.impl.io.SessionInputBufferImpl;
import org.apache.hc.core5.http.io.SessionInputBuffer;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Implementation of {@link PojaHttpServerlessApplication} for Apache.
 *
 * @author Andriy Dmytruk.
 * @since 4.10.0
 */
@Singleton
public class ApacheServerlessApplication
    extends PojaHttpServerlessApplication<ApacheServletHttpRequest<?>, ApacheServletHttpResponse<?>> {

    private final ConversionService conversionService;
    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;
    private final ExecutorService ioExecutor;
    private final ByteBufferFactory<?, ?> byteBufferFactory;
    private final ApacheServletConfiguration configuration;
    private @Nullable SessionInputBuffer sessionInputBuffer;

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
        messageBodyHandlerRegistry = applicationContext.getBean(MessageBodyHandlerRegistry.class);
        ioExecutor = applicationContext.getBean(ExecutorService.class, Qualifiers.byName(TaskExecutors.BLOCKING));
        configuration = applicationContext.getBean(ApacheServletConfiguration.class);
        byteBufferFactory = ByteArrayBufferFactory.INSTANCE;
    }

    @Override
    protected boolean handleSingleRequest(
            ServletHttpHandler<ApacheServletHttpRequest<?>, ApacheServletHttpResponse<?>> servletHttpHandler,
            InputStream in,
            OutputStream out
    ) throws IOException {
        try (ApacheResponseContext responseContext = new ApacheResponseContext(configuration, out)) {
            try {
                // The buffer is initialized only once
                if (sessionInputBuffer == null) {
                    sessionInputBuffer = new SessionInputBufferImpl(configuration.inputBufferSize());
                }
                ApacheServletHttpRequest exchange = new ApacheServletHttpRequest<>(
                    in, responseContext, sessionInputBuffer, conversionService, messageBodyHandlerRegistry, ioExecutor, byteBufferFactory
                );
                servletHttpHandler.service(exchange);
                if (!responseContext.isCommitted()) {
                    Objects.requireNonNull(responseContext.primaryResponse, "primaryResponse").getOutputStream(); // this causes the commit
                }
            } catch (Exception e) {
                if (!responseContext.isCommitted()) {
                    try (OutputStream os = responseContext.commit(new BasicClassicHttpResponse(HttpStatus.BAD_REQUEST.getCode()))) {
                        os.write(Objects.toString(e.getMessage(), "").getBytes(StandardCharsets.UTF_8));
                    }
                }
                throw e;
            }
            return !responseContext.connectionClose;
        }
    }

    @Override
    protected boolean useInheritedChannel() {
        return configuration.useInheritedChannel();
    }
}
