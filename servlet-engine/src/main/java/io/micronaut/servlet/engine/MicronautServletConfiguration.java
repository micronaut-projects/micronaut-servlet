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
import io.micronaut.context.env.Environment;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.naming.Named;
import io.micronaut.http.server.HttpServerConfiguration;

import io.micronaut.core.annotation.Nonnull;
import javax.servlet.MultipartConfigElement;
import java.io.File;
import java.util.Optional;

/**
 * Configuration properties for the Micronaut servlet.
 *
 * @author graemerocher
 * @since 1.0
 */
@ConfigurationProperties(MicronautServletConfiguration.PREFIX)
public class MicronautServletConfiguration implements Named {

    /**
     * The prefix used for configuration.
     */
    public static final String PREFIX = "micronaut.servlet";
    private final String mapping;
    private final MultipartConfigElement multipartConfigElement;
    private final String name;

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

    @Nonnull
    @Override
    public String getName() {
        return name;
    }
}
