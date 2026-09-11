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
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.core.util.StringUtils;
import io.micronaut.core.util.Toggleable;
import io.micronaut.http.server.HttpServerConfiguration;
import jakarta.inject.Inject;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.RequestLogWriter;
import org.eclipse.jetty.server.SecureRequestCustomizer;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Configuration properties for Jetty.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@ConfigurationProperties("jetty")
@Replaces(HttpServerConfiguration.class)
public class JettyConfiguration extends HttpServerConfiguration implements Toggleable {

    public static final String PREFIX = HttpServerConfiguration.PREFIX + ".jetty";
    public static final String ENABLED_PROPERTY = PREFIX + ".enabled";
    /**
     * The default value for {@link #setNativeStaticResources(boolean)}.
     */
    @SuppressWarnings("WeakerAccess")
    public static final boolean DEFAULT_NATIVE_STATIC_RESOURCES = false;

    @ConfigurationBuilder
    protected HttpConfiguration httpConfiguration = new HttpConfiguration();
    private final @Nullable JettyRequestLog requestLog;

    private final @Nullable MultipartConfiguration multipartConfiguration;
    private boolean enabled = true;
    private boolean nativeStaticResources = DEFAULT_NATIVE_STATIC_RESOURCES;
    private @Nullable Map<String, String> initParameters;

    /**
     * Default constructor.
     * @param multipartConfiguration The multipart configuration.
     */
    public JettyConfiguration(@Nullable MultipartConfiguration multipartConfiguration) {
        this(multipartConfiguration, null);
    }

    /**
     * Default constructor.
     * @param multipartConfiguration The multipart configuration.
     * @param requestLog Request log configuration.
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

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether the Jetty runtime is enabled.
     *
     * @param enabled True if the runtime is enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Whether static resources are served by Jetty's own {@code ResourceHandler} rather than by the Micronaut static
     * resource resolver that the other runtimes use.
     *
     * @return True if Jetty serves static resources itself
     * @since 6.2.0
     */
    public boolean isNativeStaticResources() {
        return nativeStaticResources;
    }

    /**
     * Serve static resources with Jetty's own {@code ResourceHandler} instead of the Micronaut static resource
     * resolver. Jetty's handler supports range requests and conditional caching natively, but sits outside the
     * Micronaut filter chain, so Micronaut filters (including CORS) do not see those requests and the
     * {@code cache-control} setting of each static resource configuration applies instead of
     * {@code micronaut.server.responses.file}. Default value ({@value #DEFAULT_NATIVE_STATIC_RESOURCES}).
     *
     * @param nativeStaticResources True to let Jetty serve static resources
     * @since 6.2.0
     */
    public void setNativeStaticResources(boolean nativeStaticResources) {
        this.nativeStaticResources = nativeStaticResources;
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
     * Configuration for additional connectors.
     */
    @EachProperty(
        value = "connectors",
        excludes = {"beans", "eventListeners", "connectionFactories"}
    )
    public static class ConnectorConfiguration extends ServerConnector {
        private boolean sslEnabled = true;

        public ConnectorConfiguration(@Parameter String name, Server server) {
            super(server, new ConnectionFactory[0]);
            setName(name);
        }

        /**
         * Whether SSL is enabled for the connector. Defaults to true
         * which means if SSL is configured it will be used.
         * @return True if SSL should be used if configured
         */
        public boolean isSslEnabled() {
            return sslEnabled;
        }

        /**
         * Set whether SSL should be used if configured.
         * @param sslEnabled Whether SSL is enabled.
         */
        public void setSslEnabled(boolean sslEnabled) {
            this.sslEnabled = sslEnabled;
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
    @Requires(property = JettyRequestLog.ENABLED_PROPERTY, value = StringUtils.TRUE)
    public static final class JettyRequestLog implements Toggleable {
        public static final String ACCESS_LOG = "access-log";
        public static final String ENABLED_PROPERTY = HttpServerConfiguration.PREFIX + ".jetty." + ACCESS_LOG + ".enabled";
        @ConfigurationBuilder(prefixes = "set", excludes = "eventListeners")
        RequestLogWriter requestLogWriter = new RequestLogWriter();

        private boolean enabled = true;
        private String pattern = CustomRequestLog.EXTENDED_NCSA_FORMAT;
        private @Nullable String fileName;
        private String resourcePath = "/logback-access.xml";
        private boolean quiet = true;

        public @Nullable String getFileName() {
            return fileName;
        }

        /**
         * @param fileName sets the fileName attribute for {@link ch.qos.logback.access.jetty.RequestLogImpl}.
         */
        public void setFileName(@Nullable String fileName) {
            this.fileName = fileName;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        public String getResourcePath() {
            return resourcePath;
        }

        /**
         * @param resourcePath sets the resourcePath attribute for {@link ch.qos.logback.access.jetty.RequestLogImpl}.
         */
        public void setResourcePath(String resourcePath) {
            this.resourcePath = resourcePath;
        }

        public boolean isQuiet() {
            return quiet;
        }

        /**
         * @param quiet sets the quiet attribute for {@link ch.qos.logback.access.jetty.RequestLogImpl}.
         */
        public void setQuiet(boolean quiet) {
            this.quiet = quiet;
        }

        /**
         * Whether access log is enabled.
         *
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
