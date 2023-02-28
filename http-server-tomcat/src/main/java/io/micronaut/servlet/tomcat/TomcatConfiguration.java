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
package io.micronaut.servlet.tomcat;

import java.util.Optional;

import io.micronaut.context.annotation.ConfigurationBuilder;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.TypeHint;
import io.micronaut.http.server.HttpServerConfiguration;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.ajp.AjpNio2Protocol;
import org.apache.coyote.ajp.AjpNioProtocol;
import org.apache.coyote.http11.Http11Nio2Protocol;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.coyote.http2.Http2Protocol;

/**
 * Extends {@link HttpServerConfiguration} and allows configuring Tomcat.
 *
 * @author graemerocher
 * @since 1.0
 */
@ConfigurationProperties("tomcat")
@TypeHint({
        TomcatConfiguration.class,
        Http11NioProtocol.class,
        Http11Nio2Protocol.class,
        Http2Protocol.class,
        AjpNio2Protocol.class,
        AjpNioProtocol.class
})
@Replaces(HttpServerConfiguration.class)
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

}
