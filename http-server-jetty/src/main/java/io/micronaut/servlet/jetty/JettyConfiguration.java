package io.micronaut.servlet.jetty;

import io.micronaut.context.annotation.ConfigurationBuilder;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.http.server.HttpServerConfiguration;
import org.eclipse.jetty.server.HttpConfiguration;

/**
 * Configuration properties for Jetty.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@ConfigurationProperties("jetty")
public class JettyConfiguration extends HttpServerConfiguration {

    @ConfigurationBuilder("http")
    protected HttpConfiguration httpConfiguration = new HttpConfiguration();

    @ConfigurationBuilder("https")
    protected HttpConfiguration httpsConfiguration = new HttpConfiguration();

    public JettyConfiguration() {
        httpsConfiguration.setSecureScheme("https");
    }

    /**
     * @return The HTTP configuration instance
     */
    public HttpConfiguration getHttpConfiguration() {
        return httpConfiguration;
    }

    /**
     * @return The HTTPS configuration
     */
    public HttpConfiguration getHttpsConfiguration() {
        return httpsConfiguration;
    }
}
