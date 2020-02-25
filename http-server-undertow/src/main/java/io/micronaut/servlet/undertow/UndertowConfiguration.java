package io.micronaut.servlet.undertow;

import io.micronaut.context.annotation.ConfigurationBuilder;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.http.server.HttpServerConfiguration;
import io.undertow.Undertow;

@ConfigurationProperties("undertow")
public class UndertowConfiguration extends HttpServerConfiguration {

    @ConfigurationBuilder
    protected Undertow.Builder undertowBuilder = Undertow.builder();

    /**
     * @return The undertow builder
     */
    public Undertow.Builder getUndertowBuilder() {
        return undertowBuilder;
    }
}
