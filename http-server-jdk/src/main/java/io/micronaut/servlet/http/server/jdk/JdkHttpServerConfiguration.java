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
package io.micronaut.servlet.http.server.jdk;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.util.Toggleable;
import io.micronaut.http.server.HttpServerConfiguration;

/**
 * Configuration for the built-in JDK HTTP server runtime.
 */
@ConfigurationProperties("jdk")
@Replaces(HttpServerConfiguration.class)
public class JdkHttpServerConfiguration extends HttpServerConfiguration implements Toggleable {

    public static final String PREFIX = HttpServerConfiguration.PREFIX + ".jdk";
    public static final String ENABLED_PROPERTY = PREFIX + ".enabled";

    private boolean enabled = true;

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether the JDK HTTP server runtime is enabled.
     *
     * @param enabled True if the runtime is enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
