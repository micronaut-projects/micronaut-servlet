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
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.poja.PojaHttpServerlessApplication;
import io.micronaut.http.poja.exception.NoPojaRequestException;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.servlet.http.ServletHttpHandler;
import jakarta.inject.Singleton;
import org.apache.hc.core5.http.impl.io.SessionInputBufferImpl;
import org.apache.hc.core5.http.io.SessionInputBuffer;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
    private final MediaTypeCodecRegistry codecRegistry;
    private final ExecutorService ioExecutor;
    private final ByteBufferFactory<?, ?> byteBufferFactory;
    private final ApacheServletConfiguration configuration;
    private final ThreadLocal<SessionInputBuffer> sessionInputBuffer;

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
        sessionInputBuffer = ThreadLocal.withInitial(() -> new SessionInputBufferImpl(configuration.inputBufferSize()));
        byteBufferFactory = ByteArrayBufferFactory.INSTANCE;
    }

    @Override
    protected void runIndefinitely(
            ServletHttpHandler<ApacheServletHttpRequest<?>, ApacheServletHttpResponse<?>> servletHttpHandler,
            InputStream in,
            OutputStream out
    ) throws IOException {
        try {
            super.runIndefinitely(servletHttpHandler, in, out);
        } finally {
            // Each connection owns its parser buffer, including when worker threads are reused.
            sessionInputBuffer.remove();
        }
    }

    @Override
    protected boolean handleSingleRequest(
            ServletHttpHandler<ApacheServletHttpRequest<?>, ApacheServletHttpResponse<?>> servletHttpHandler,
            InputStream in,
            OutputStream out
    ) throws IOException {
        try (ApacheResponseContext responseContext = new ApacheResponseContext(configuration, out)) {
            try {
                ApacheServletHttpRequest exchange = new ApacheServletHttpRequest<>(
                    in, responseContext, sessionInputBuffer.get(), conversionService, codecRegistry, ioExecutor, byteBufferFactory
                );
                servletHttpHandler.service(exchange);
                if (!responseContext.isCommitted()) {
                    responseContext.primaryResponse.getOutputStream(); // this causes the commit
                }
            } catch (NoPojaRequestException e) {
                // A clean end of input is not a malformed request and must not emit a 400 response.
                throw e;
            } catch (Exception e) {
                if (!responseContext.isCommitted()) {
                    try (OutputStream os = responseContext.commit(new BasicClassicHttpResponse(HttpStatus.BAD_REQUEST.getCode()))) {
                        os.write(e.getMessage().getBytes(StandardCharsets.UTF_8));
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
