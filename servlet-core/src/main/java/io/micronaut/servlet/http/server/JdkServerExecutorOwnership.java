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
package io.micronaut.servlet.http.server;

import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.Internal;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records the request executor this module created, so stopping the server shuts down that one and no other.
 *
 * <p>An application is free to supply its own {@code HttpServer} bean carrying a shared or externally managed
 * executor. Shutting that down when the embedded server stops would interrupt work the application still needs, so
 * only an executor created here is ever closed.</p>
 *
 * @since 6.2.0
 */
@Internal
@Experimental
@Singleton
final class JdkServerExecutorOwnership {

    private final AtomicReference<ExecutorService> created = new AtomicReference<>();

    /**
     * @param executorService The executor this module created for the server
     */
    void owns(ExecutorService executorService) {
        created.set(executorService);
    }

    /**
     * @param executorService The executor the server is currently using
     * @return Whether that executor was created by this module and may therefore be shut down with the server
     */
    boolean isOwned(@Nullable Object executorService) {
        return executorService != null && executorService == created.get();
    }
}
