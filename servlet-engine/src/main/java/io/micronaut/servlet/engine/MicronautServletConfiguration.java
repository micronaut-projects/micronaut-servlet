package io.micronaut.servlet.engine;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.env.Environment;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.naming.Named;
import io.micronaut.http.server.HttpServerConfiguration;

import javax.annotation.Nonnull;
import javax.servlet.MultipartConfigElement;
import java.io.File;
import java.util.Optional;

/**
 * Configuration properties for the Micronaut servlet.
 *
 * @author graemerocher
 * @since 1.0
 */
@ConfigurationProperties("micronaut.servlet")
public class MicronautServletConfiguration implements Named {

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
