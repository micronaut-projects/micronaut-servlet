package io.micronaut.servlet.engine.server;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.core.annotation.Indexed;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.util.StringUtils;
import io.micronaut.core.util.Toggleable;
import io.micronaut.web.router.resource.StaticResourceConfiguration;

import java.util.List;

/**
 * Configuration for static resources for servlet engines.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@EachProperty(StaticResourceConfiguration.PREFIX)
@Indexed(ServletStaticResourceConfiguration.class)
public interface ServletStaticResourceConfiguration extends Toggleable {

    String CLASSPATH_PREFIX = "classpath:";
    String FILE_PREFIX = "file:";

    /**
     * @return The mapping
     */
    @Bindable(defaultValue = StaticResourceConfiguration.DEFAULT_MAPPING)
    String getMapping();

    /**
     * @return The paths
     */
    List<String> getPaths();

    @Override
    @Bindable(defaultValue = StringUtils.TRUE)
    boolean isEnabled();
}
