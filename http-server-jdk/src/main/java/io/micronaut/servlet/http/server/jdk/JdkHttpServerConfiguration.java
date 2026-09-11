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

import java.util.Collections;
import java.util.List;

/**
 * Configuration for the built-in JDK HTTP server runtime.
 */
@ConfigurationProperties("jdk")
@Replaces(HttpServerConfiguration.class)
public class JdkHttpServerConfiguration extends HttpServerConfiguration implements Toggleable {

    public static final String PREFIX = HttpServerConfiguration.PREFIX + ".jdk";
    public static final String ENABLED_PROPERTY = PREFIX + ".enabled";

    private boolean enabled = true;
    private AccessLogger accessLogger = new AccessLogger();

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * The access log configuration.
     *
     * @return The access log configuration
     * @since 6.2.0
     */
    public AccessLogger getAccessLogger() {
        return accessLogger;
    }

    /**
     * Sets the access log configuration.
     *
     * @param accessLogger The access log configuration
     * @since 6.2.0
     */
    public void setAccessLogger(AccessLogger accessLogger) {
        if (accessLogger != null) {
            this.accessLogger = accessLogger;
        }
    }

    /**
     * Sets whether the JDK HTTP server runtime is enabled.
     *
     * @param enabled True if the runtime is enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Access log configuration for the built-in server, named as the Netty server's
     * {@code micronaut.server.netty.access-logger}. Each request is logged in the common log format to the
     * configured logger once its response has been sent.
     *
     * @since 6.2.0
     */
    @ConfigurationProperties("access-logger")
    public static class AccessLogger implements Toggleable {
        /**
         * The default logger name.
         */
        public static final String DEFAULT_LOGGER_NAME = "HTTP_ACCESS_LOGGER";

        private boolean enabled;
        private String loggerName = DEFAULT_LOGGER_NAME;
        private List<String> exclusions = Collections.emptyList();

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Enables the access log. Default value ({@code false}).
         *
         * @param enabled Whether the access log is enabled
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * @return The name of the logger the access log is written to
         */
        public String getLoggerName() {
            return loggerName;
        }

        /**
         * Sets the logger name. Default value ({@value #DEFAULT_LOGGER_NAME}).
         *
         * @param loggerName The logger name
         */
        public void setLoggerName(String loggerName) {
            if (loggerName != null) {
                this.loggerName = loggerName;
            }
        }

        /**
         * @return Regular expressions matched against the request path; matching requests are not logged
         */
        public List<String> getExclusions() {
            return exclusions;
        }

        /**
         * Sets the paths to exclude from the log, as regular expressions matched against the request path.
         *
         * @param exclusions The exclusion patterns
         */
        public void setExclusions(List<String> exclusions) {
            this.exclusions = exclusions == null ? Collections.emptyList() : exclusions;
        }
    }
}
