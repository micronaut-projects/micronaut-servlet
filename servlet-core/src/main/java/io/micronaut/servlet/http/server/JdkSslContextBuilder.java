/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.servlet.http.server;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.HttpVersion;
import io.micronaut.http.server.exceptions.HttpServerException;
import io.micronaut.http.server.exceptions.ServerStartupException;
import io.micronaut.http.ssl.SslBuilder;
import io.micronaut.http.ssl.SslConfiguration;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * Builds the {@link SSLContext} the built-in Java HTTP server uses when {@code micronaut.ssl.enabled} is set, from
 * the same {@code micronaut.server.ssl} configuration the servlet containers read.
 *
 * @since 6.2.0
 */
@Internal
final class JdkSslContextBuilder extends SslBuilder<SSLContext> {

    JdkSslContextBuilder(ResourceResolver resourceResolver) {
        super(resourceResolver);
    }

    @Override
    public Optional<SSLContext> build(SslConfiguration ssl) {
        return build(ssl, HttpVersion.HTTP_1_1);
    }

    @Override
    public Optional<SSLContext> build(SslConfiguration ssl, HttpVersion httpVersion) {
        if (!ssl.isEnabled()) {
            return Optional.empty();
        }
        String protocol = ssl.getProtocol().orElseThrow(() -> new ServerStartupException("No SSL protocol specified"));
        try {
            SSLContext sslContext = SSLContext.getInstance(protocol);
            KeyManagerFactory keyManagerFactory = getKeyManagerFactory(ssl);
            KeyManager @Nullable [] keyManagers = keyManagerFactory == null ? null : keyManagerFactory.getKeyManagers();
            TrustManagerFactory trustManagerFactory = getTrustManagerFactory(ssl);
            TrustManager @Nullable [] trustManagers = trustManagerFactory == null ? null : trustManagerFactory.getTrustManagers();
            sslContext.init(keyManagers, trustManagers, new SecureRandom());
            return Optional.of(sslContext);
        } catch (Exception e) {
            throw new HttpServerException("HTTPS configuration error: " + e.getMessage(), e);
        }
    }
}
