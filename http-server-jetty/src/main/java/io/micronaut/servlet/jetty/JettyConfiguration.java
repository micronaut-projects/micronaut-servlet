package io.micronaut.servlet.jetty;

import io.micronaut.context.annotation.ConfigurationBuilder;
import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.http.server.HttpServerConfiguration;
import org.eclipse.jetty.server.HttpConfiguration;

import javax.annotation.Nullable;
import javax.servlet.MultipartConfigElement;
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

    @ConfigurationProperties("multipart")
    public static class MultipartConfiguration extends MultipartConfigElement {

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
