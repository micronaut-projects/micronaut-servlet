/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.servlet.engine;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.naming.Named;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.servlet.http.ServletConfiguration;

import jakarta.servlet.MultipartConfigElement;
import java.io.File;
import java.util.Optional;

/**
 * Configuration properties for the Micronaut servlet.
 *
 * @author graemerocher
 * @since 1.0
 */
@ConfigurationProperties(MicronautServletConfiguration.PREFIX)
public class MicronautServletConfiguration implements Named, ServletConfiguration {

    /**
     * The prefix used for configuration.
     */
    public static final String PREFIX = "micronaut.servlet";
    private final String mapping;
    private final MultipartConfigElement multipartConfigElement;
    private final String name;
    private boolean asyncFileServingEnabled = true;

    private boolean asyncSupported = true;
    private boolean enableVirtualThreads = true;

    private Integer minThreads;
    private Integer maxThreads;


    /**
     * Default constructor.
     * @param name The name of the servlet
     * @param mapping The servlet mapping
     * @param serverConfiguration The http server configuration
     */
    @ConfigurationInject
    public MicronautServletConfiguration(
            @Bindable(defaultValue = Environment.MICRONAUT) String name,
            @Bindable(defaultValue = "/*") String mapping,
            HttpServerConfiguration serverConfiguration) {
        this.mapping = mapping != null ? mapping : "/*";
        this.name = name != null ? name : Environment.MICRONAUT;
        final HttpServerConfiguration.MultipartConfiguration multipart = serverConfiguration.getMultipart();
        if (multipart != null && multipart.isEnabled()) {
            this.multipartConfigElement = new MultipartConfigElement(
                    multipart.getLocation().map(File::getAbsolutePath).orElse(null),
                    multipart.getMaxFileSize(),
                    serverConfiguration.getMaxRequestSize(),
                    (int) multipart.getThreshold()
            );
        } else {
            this.multipartConfigElement = null;
        }
    }

    @Override
    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    /**
     * Set whether async is supported or not.
     * @param asyncSupported True if async is supported.
     */
    public void setAsyncSupported(boolean asyncSupported) {
        this.asyncSupported = asyncSupported;
    }

    /**
     * Legacy property to disable async for testing.
     *
     * @param asyncSupported Is async supported
     * @deprecated Use {@link #setAsyncSupported(boolean)} instead
     */
    @Deprecated(forRemoval = true, since = "4.8.0")
    @Property(name = "micronaut.server.testing.async")
    public void setTestAsyncSupported(@Nullable Boolean asyncSupported) {
        if (asyncSupported != null) {
            this.asyncSupported = asyncSupported;
        }
    }

    @Override
    public boolean isEnableVirtualThreads() {
        return this.enableVirtualThreads;
    }

    /**
     * Whether virtual threads are enabled.
     * @param enableVirtualThreads True if they are enabled
     * @since 4.8.0
     */
    public void setEnableVirtualThreads(boolean enableVirtualThreads) {
        this.enableVirtualThreads = enableVirtualThreads;
    }

    /**
     * @return The servlet mapping.
     */
    public String getMapping() {
        return mapping;
    }

    /**
     * @return The configured multipart element if any
     */
    public Optional<MultipartConfigElement> getMultipartConfigElement() {
        return Optional.ofNullable(multipartConfigElement);
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    /**
     * Is async file serving enabled.
     * @param enabled True if it is
     */
    public void setAsyncFileServingEnabled(boolean enabled) {
        this.asyncFileServingEnabled = enabled;
    }

    @Override
    public boolean isAsyncFileServingEnabled() {
        return asyncSupported && asyncFileServingEnabled;
    }

    @Override
    public Integer getMinThreads() {
        return minThreads;
    }

    /**
     * Specify the minimum number of threads in the created thread pool.
     *
     * @param minThreads The minimum number of threads
     */
    public void setMinThreads(Integer minThreads) {
        this.minThreads = minThreads;
    }

    @Override
    public Integer getMaxThreads() {
        return maxThreads;
    }

    /**
     * Specify the maximum number of threads in the created thread pool.
     *
     * @param maxThreads The maximum number of threads
     */
    public void setMaxThreads(Integer maxThreads) {
        this.maxThreads = maxThreads;
    }
}
