/*
 * Copyright 2017-2023 original authors
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

/**
 * Configuration for the servlet environment.
 */
public interface ServletConfiguration {
    /**
     * The default configuration.
     */
    ServletConfiguration DEFAULT = () -> true;

    /**
     * Is async file serving enabled.
     * @return True if it is.
     */
    boolean isAsyncFileServingEnabled();

    /**
     * Whether to do request processing asynchronously by default (defaults to {@code true}).
     * @return True whether async is enabled
     * @since 4.8.0
     */
    default boolean isAsyncSupported() {
        return true;
    }

    /**
     * Whether to enable virtual thread support if available.
     *
     * <p>If virtual threads are not available this option does nothing.</p>
     *
     * @return True if they should be enabled
     * @since 4.8.0
     */
    default boolean isEnableVirtualThreads() {
        return true;
    }

    /**
     * Get the minimum number of threads in the created thread pool.
     *
     * @return The minimum number of threads
     */
    default Integer getMinThreads() {
        return null;
    }

    /**
     * Get the maximum number of threads in the created thread pool.
     *
     * @return The maximum number of threads
     */
    default Integer getMaxThreads() {
        return null;
    }

}
