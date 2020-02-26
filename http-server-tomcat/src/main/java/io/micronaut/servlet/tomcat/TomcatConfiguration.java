package io.micronaut.servlet.tomcat;

import io.micronaut.context.annotation.*;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.server.HttpServerConfiguration;
import org.apache.catalina.connector.Connector;

import javax.annotation.Nullable;
import javax.servlet.MultipartConfigElement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Extends {@link HttpServerConfiguration} and allows configuring Tomcat.
 *
 * @author graemerocher
 * @since 1.0
 */
@ConfigurationProperties("tomcat")
public class TomcatConfiguration extends HttpServerConfiguration {

    @ConfigurationBuilder
    protected final Connector tomcatConnector;
    private final MultipartConfiguration multipartConfiguration;
    private String protocol;

    /**
     * Default constructor.
     * @param multipartConfiguration The multipart config
     * @param protocol The protocol to use
     */
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
     * @param attributes The connector attributes
     */
    public void setAttributes(@MapFormat(
            transformation = MapFormat.MapTransformation.FLAT,
            keyFormat = StringConvention.RAW) Map<String, String> attributes) {
        if (CollectionUtils.isNotEmpty(attributes)) {
            attributes.forEach(tomcatConnector::setAttribute);
        }
    }

    /**
     * @return The protocol to use. Defaults to org.apache.coyote.http11.Http11NioProtocol.
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * @param protocol The protocol to use. Defaults to org.apache.coyote.http11.Http11NioProtocol.
     */
    public void setProtocol(@Nullable String protocol) {
        this.protocol = protocol;
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
