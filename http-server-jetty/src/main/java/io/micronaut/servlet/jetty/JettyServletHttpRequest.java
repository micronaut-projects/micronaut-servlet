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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.servlet.engine.DefaultServletHttpRequest;
import io.micronaut.servlet.http.BodyBuilder;
import io.micronaut.servlet.http.SSLSessionProvider;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletApiRequest;
import org.eclipse.jetty.ee10.servlet.ServletCoreRequest;

import java.io.IOException;
import java.util.concurrent.Executor;

@Internal
final class JettyServletHttpRequest<B> extends DefaultServletHttpRequest<B> {
    JettyServletHttpRequest(ConversionService conversionService,
                            HttpServletRequest delegate,
                            HttpServletResponse response,
                            MessageBodyHandlerRegistry messageBodyHandlerRegistry,
                            BodyBuilder bodyBuilder,
                            Executor ioExecutor,
                            SSLSessionProvider sslSessionProvider) {
        super(conversionService, delegate, response, messageBodyHandlerRegistry, bodyBuilder, ioExecutor, sslSessionProvider);
    }

    @Override
    protected boolean prepareUnusedFormBodyForResponse() {
        ServletRequest request = delegate();
        if (request instanceof HttpServletRequest httpServletRequest) {
            try {
                ServletInputStream inputStream = httpServletRequest.getInputStream();
                byte[] buffer = new byte[8192];
                while (true) {
                    int read = inputStream.read(buffer);
                    if (read == -1) {
                        break;
                    }
                }
            } catch (IOException ignored) {
                // Fall through to Jetty's best-effort request consumption below.
            }
            return ServletCoreRequest.wrap(httpServletRequest).consumeAvailable();
        }
        if (request instanceof ServletApiRequest servletApiRequest) {
            return servletApiRequest.getRequest().consumeAvailable();
        }
        return false;
    }
}
