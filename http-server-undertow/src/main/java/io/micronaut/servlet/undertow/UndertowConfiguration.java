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
package io.micronaut.servlet.undertow;

import io.micronaut.context.annotation.ConfigurationBuilder;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.TypeHint;
import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.http.server.HttpServerConfiguration;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;

import io.micronaut.core.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration for the Undertow server.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@ConfigurationProperties("undertow")
@TypeHint(
        value = {UndertowOptions.class, org.xnio.Option.class},
        accessType = TypeHint.AccessType.ALL_DECLARED_FIELDS
)
@Replaces(HttpServerConfiguration.class)
public class UndertowConfiguration extends HttpServerConfiguration {

    @ConfigurationBuilder
    protected Undertow.Builder undertowBuilder = Undertow.builder();

    private final MultipartConfiguration multipartConfiguration;
    private Map<String, String> workerOptions = new HashMap<>(5);
    private Map<String, String> socketOptions = new HashMap<>(5);
    private Map<String, String> serverOptions = new HashMap<>(5);

    /**
     * Default constructor.
     * @param multipartConfiguration The multipart configuration
     */
    public UndertowConfiguration(@Nullable MultipartConfiguration multipartConfiguration) {
        this.multipartConfiguration = multipartConfiguration;
    }

    /**
     * @return The undertow builder
     */
    public Undertow.Builder getUndertowBuilder() {
        return undertowBuilder;
    }

    /**
     * @return The multipart configuration
     */
    public Optional<MultipartConfiguration> getMultipartConfiguration() {
        return Optional.ofNullable(multipartConfiguration);
    }

    /**
     * @return The worker options
     */
    public Map<String, String> getWorkerOptions() {
        return workerOptions;
    }

    /**
     * Sets the worker options.
     * @param workerOptions The worker options
     */
    public void setWorkerOptions(
            @MapFormat(keyFormat = StringConvention.UNDER_SCORE_SEPARATED,
                       transformation = MapFormat.MapTransformation.FLAT)
            Map<String, String> workerOptions) {
        if (workerOptions != null) {
            this.workerOptions.putAll(workerOptions);
        }
    }

    /**
     * @return The socket options.
     */
    public Map<String, String> getSocketOptions() {
        return socketOptions;
    }

    /**
     * Sets the socket options.
     * @param socketOptions The socket options
     */
    public void setSocketOptions(
            @MapFormat(keyFormat = StringConvention.UNDER_SCORE_SEPARATED,
                       transformation = MapFormat.MapTransformation.FLAT)
            Map<String, String> socketOptions) {
        if (socketOptions != null) {
            this.socketOptions.putAll(socketOptions);
        }
    }

    /**
     * @return The server options.
     */
    public Map<String, String> getServerOptions() {
        return serverOptions;
    }

    /**
     * @param serverOptions Sets the server options
     */
    public void setServerOptions(
            @MapFormat(keyFormat = StringConvention.UNDER_SCORE_SEPARATED,
                       transformation = MapFormat.MapTransformation.FLAT)
            Map<String, String> serverOptions) {
        if (serverOptions != null) {
            this.serverOptions.putAll(serverOptions);
        }
    }
}
