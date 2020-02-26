package io.micronaut.servlet.undertow;

import io.micronaut.context.annotation.ConfigurationBuilder;
import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.http.server.HttpServerConfiguration;
import io.undertow.Undertow;

import javax.annotation.Nullable;
import javax.servlet.MultipartConfigElement;
import java.util.Optional;

/**
 * Configuration for the Undertow server.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@ConfigurationProperties("undertow")
public class UndertowConfiguration extends HttpServerConfiguration {

    @ConfigurationBuilder
    protected Undertow.Builder undertowBuilder = Undertow.builder();

    private final MultipartConfiguration multipartConfiguration;

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
     * The multipart configuration.
     */
    @ConfigurationProperties("multipart")
    public static class MultipartConfiguration extends MultipartConfigElement {

        /**
         * Default constructor.
         * @param location The location
         * @param maxFileSize The file size
         * @param maxRequestSize The max request size
         * @param fileSizeThreshold The threshold
         */
        @ConfigurationInject
        public MultipartConfiguration(
                @Nullable String location,
                @Bindable(defaultValue = "-1") long maxFileSize,
                @Bindable(defaultValue = "-1") long maxRequestSize,
                @Bindable(defaultValue = "0") int fileSizeThreshold) {
            super(location, maxFileSize, maxRequestSize, fileSizeThreshold);
        }
    }
}
