/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
