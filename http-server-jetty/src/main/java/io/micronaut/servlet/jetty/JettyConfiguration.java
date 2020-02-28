package io.micronaut.servlet.jetty;

import io.micronaut.context.annotation.ConfigurationBuilder;
import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.http.server.HttpServerConfiguration;
import org.eclipse.jetty.server.HttpConfiguration;

import javax.annotation.Nullable;
import javax.servlet.MultipartConfigElement;
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
public class JettyConfiguration extends HttpServerConfiguration {

    @ConfigurationBuilder
    protected HttpConfiguration httpConfiguration = new HttpConfiguration();

    private final MultipartConfiguration multipartConfiguration;
    private Map<String, String> initParameters;

    /**
     * Default constructor.
     * @param multipartConfiguration The multipart configuration.
     */
    public JettyConfiguration(@Nullable MultipartConfiguration multipartConfiguration) {
        this.multipartConfiguration = multipartConfiguration;
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
