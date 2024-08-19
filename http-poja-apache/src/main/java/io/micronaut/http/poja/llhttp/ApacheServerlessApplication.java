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
package io.micronaut.http.poja.llhttp;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.poja.PojaHttpServerlessApplication;
import io.micronaut.http.poja.llhttp.exception.ApacheServletBadRequestException;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.servlet.http.ServletHttpHandler;
import jakarta.inject.Singleton;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.impl.io.DefaultHttpResponseWriter;
import org.apache.hc.core5.http.impl.io.SessionOutputBufferImpl;
import org.apache.hc.core5.http.io.SessionOutputBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    }

    @Override
    protected void handleSingleRequest(
            ServletHttpHandler<ApacheServletHttpRequest<?>, ApacheServletHttpResponse<?>> servletHttpHandler,
            InputStream in,
            OutputStream out
    ) throws IOException {
        ApacheServletHttpResponse<?> response = new ApacheServletHttpResponse<>(conversionService);
        try {
            ApacheServletHttpRequest exchange = new ApacheServletHttpRequest<>(
                in, conversionService, codecRegistry, ioExecutor, response
            );
            servletHttpHandler.service(exchange);
        } catch (ApacheServletBadRequestException e) {
            response.status(HttpStatus.BAD_REQUEST);
            response.contentType(MediaType.TEXT_PLAIN_TYPE);
            response.getOutputStream().write(e.getMessage().getBytes());
        }
        writeResponse(response.getNativeResponse(), out);
    }

    private void writeResponse(ClassicHttpResponse response, OutputStream out) throws IOException {
        SessionOutputBuffer buffer = new SessionOutputBufferImpl(8 * 1024);
        DefaultHttpResponseWriter responseWriter = new DefaultHttpResponseWriter();
        try {
            responseWriter.write(response, buffer, out);
        } catch (HttpException e) {
            throw new HttpServerException("Could not write response body", e);
        }
        buffer.flush(out);

        HttpEntity entity = response.getEntity();
        if (entity != null) {
            entity.writeTo(out);
        }
        out.flush();
    }

}
