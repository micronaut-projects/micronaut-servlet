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
package io.micronaut.servlet.jetty;

import io.micronaut.context.annotation.ConfigurationBuilder;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.core.util.StringUtils;
import io.micronaut.core.util.Toggleable;
import io.micronaut.http.server.HttpServerConfiguration;
import jakarta.inject.Inject;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.HttpConfiguration;

import io.micronaut.core.annotation.Nullable;
import org.eclipse.jetty.server.RequestLogWriter;
import org.eclipse.jetty.server.SecureRequestCustomizer;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration properties for Jetty.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@ConfigurationProperties("jetty")
@Replaces(HttpServerConfiguration.class)
public class JettyConfiguration extends HttpServerConfiguration {

    @ConfigurationBuilder
    protected HttpConfiguration httpConfiguration = new HttpConfiguration();
    private final JettyRequestLog requestLog;

    private final MultipartConfiguration multipartConfiguration;
    private Map<String, String> initParameters;

    /**
     * Default constructor.
     * @param multipartConfiguration The multipart configuration.
     */
    public JettyConfiguration(@Nullable MultipartConfiguration multipartConfiguration) {
        this(null, null);
    }

    /**
     * Default constructor.
     * @param multipartConfiguration The multipart configuration.
     */
    @Inject
    public JettyConfiguration(@Nullable MultipartConfiguration multipartConfiguration, @Nullable JettyRequestLog requestLog) {
        this.multipartConfiguration = multipartConfiguration;
        this.requestLog = requestLog;
    }

    /**
     * @return The HTTP configuration instance
     */
    public HttpConfiguration getHttpConfiguration() {
        return httpConfiguration;
    }

    /**
     * @return The multipart configuration
     */
    public Optional<MultipartConfiguration> getMultipartConfiguration() {
        return Optional.ofNullable(multipartConfiguration);
    }

    /**
     * @return The request log configuration.
     */
    public Optional<JettyRequestLog> getRequestLog() {
        return Optional.ofNullable(requestLog);
    }

    /**
     * @return The servlet init parameters
     */
    public Map<String, String> getInitParameters() {
        if (initParameters != null) {
            return Collections.unmodifiableMap(initParameters);
        } else {
            return Collections.emptyMap();
        }
    }

    /**
     * Sets the servlet init parameters.
     * @param initParameters The init parameters
     */
    public void setInitParameters(
            @MapFormat(transformation = MapFormat.MapTransformation.FLAT,
            keyFormat = StringConvention.RAW) Map<String, String> initParameters) {
        if (initParameters != null) {
            this.initParameters = initParameters;
        }
    }

    /**
     * The default connection pool configuration.
     */
    @ConfigurationProperties("ssl")
    public static class JettySslConfiguration extends SecureRequestCustomizer {
    }

    /**
     * Jetty access log configuration.
     *
     * @since 4.8.0
     */
    @ConfigurationProperties(JettyRequestLog.ACCESS_LOG)
    @Requires(property = JettyRequestLog.ENABLED, value = StringUtils.TRUE)
    public static class JettyRequestLog implements Toggleable {
        public static final String ACCESS_LOG = "access-log";
        public static final String ENABLED = JettyConfiguration.PREFIX + ".jetty." + ACCESS_LOG + ".enabled";
        @ConfigurationBuilder(prefixes = "set", excludes = "eventListeners")
        RequestLogWriter requestLogWriter = new RequestLogWriter();

        private boolean enabled = true;
        private String pattern = CustomRequestLog.EXTENDED_NCSA_FORMAT;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Whether access log is enabled.
         * @param enabled True if it is enabled.
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * The pattern to use for the access log. Defaults to {@code EXTENDED_NCSA_FORMAT}.
         *
         * @return The pattern.
         */
        public @NonNull String getPattern() {
            return pattern;
        }

        /**
         * Sets the pattern to use for the access log. Defaults to CustomRequestLog.EXTENDED_NCSA_FORMAT.
         *
         * @param pattern The pattern
         */
        public void setPattern(String pattern) {
            this.pattern = pattern;
        }
    }
}
