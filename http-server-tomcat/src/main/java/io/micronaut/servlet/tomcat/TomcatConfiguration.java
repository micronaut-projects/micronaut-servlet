package io.micronaut.servlet.tomcat;

import io.micronaut.context.annotation.*;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.http.server.HttpServerConfiguration;
import org.apache.catalina.connector.Connector;

import javax.annotation.Nullable;
import javax.servlet.MultipartConfigElement;
import java.util.Optional;

@ConfigurationProperties("tomcat")
public class TomcatConfiguration extends HttpServerConfiguration {

    @ConfigurationBuilder
    protected final Connector tomcatConnector;
    private final MultipartConfiguration multipartConfiguration;

    public TomcatConfiguration(
            @Nullable MultipartConfiguration multipartConfiguration,
            @Property(name = HttpServerConfiguration.PREFIX + ".tomcat.protocol")
            @Nullable String protocol) {
        this.multipartConfiguration = multipartConfiguration;
        this.tomcatConnector = new Connector(
                protocol != null ? protocol : "org.apache.coyote.http11.Http11NioProtocol"
        );
    }

    /**
     * @return The tomcat connector.
     */
    public Connector getTomcatConnector() {
        return tomcatConnector;
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
