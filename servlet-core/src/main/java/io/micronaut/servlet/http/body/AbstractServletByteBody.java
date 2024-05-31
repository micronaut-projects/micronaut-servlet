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
package io.micronaut.servlet.http.body;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.body.CloseableByteBody;

/**
 * Base class for servlet {@link io.micronaut.http.body.ByteBody} implementations.
 *
 * @author Jonas Konrad
 * @since 4.9.0
 */
@Internal
abstract class AbstractServletByteBody implements CloseableByteBody {
    static void failClaim() {
        throw new IllegalStateException("Request body has already been claimed: Two conflicting sites are trying to access the request body. If this is intentional, the first user must ByteBody#split the body. To find out where the body was claimed, turn on TRACE logging for io.micronaut.http.server.netty.body.NettyByteBody.");
    }
}
