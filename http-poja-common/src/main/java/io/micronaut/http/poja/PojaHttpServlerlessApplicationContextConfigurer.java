package io.micronaut.http.poja;

import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.context.annotation.ContextConfigurer;
import io.micronaut.core.annotation.NonNull;

/**
 * A class to configure application with POJA serverless specifics.
 */
@ContextConfigurer
public final class PojaHttpServlerlessApplicationContextConfigurer implements ApplicationContextConfigurer {

    @Override
    public void configure(@NonNull ApplicationContextBuilder builder) {
        // Need to disable banner because Micronaut prints banner to STDOUT,
        // which gets mixed with HTTP response. See GCN-4489.
        builder.banner(false);
    }

}
