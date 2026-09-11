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
package io.micronaut.servlet.http;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.buffer.ReadBuffer;
import io.micronaut.core.io.buffer.ReadBufferFactory;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.netty.NettyHttpResponseBuilder;
import io.micronaut.http.netty.stream.StreamedHttpResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.HttpContent;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * Recognises responses whose body is a Netty content stream rather than an object, as produced by the Netty HTTP
 * client's {@code ProxyHttpClient}: a filter that proxies a request returns one of these, with no {@code body()}
 * to encode and the bytes only reachable through {@link NettyHttpResponseBuilder}. The Netty server unwraps them in
 * its own response lifecycle; this does the same for the servlet one.
 *
 * <p>Only loaded once the caller has checked that {@code micronaut-http-netty} is on the classpath, so that it stays
 * an optional dependency.</p>
 *
 * @since 6.2.0
 */
@Internal
final class NettyStreamedResponses {

    private NettyStreamedResponses() {
    }

    /**
     * Extracts the content stream of a response backed by a Netty streamed response.
     *
     * @param response The response
     * @param bufferFactory The factory used to adapt each chunk
     * @return The content as buffers, or {@code null} if the response is not a Netty content stream
     */
    static @Nullable Publisher<ReadBuffer> streamedContent(HttpResponse<?> response, ReadBufferFactory bufferFactory) {
        if (response instanceof NettyHttpResponseBuilder builder && builder.isStream()
            && builder.toHttpResponse() instanceof StreamedHttpResponse streamed) {
            return Flux.from(streamed).map(content -> copy(content, bufferFactory));
        }
        return null;
    }

    private static ReadBuffer copy(HttpContent content, ReadBufferFactory bufferFactory) {
        ByteBuf buf = content.content();
        try {
            return bufferFactory.adapt(ByteBufUtil.getBytes(buf));
        } finally {
            content.release();
        }
    }
}
