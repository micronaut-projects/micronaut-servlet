/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.servlet.engine.initializer;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletSecurityElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

import static io.micronaut.core.util.StringUtils.isEmpty;

/**
 * A servlet initializer for Micronaut for deployment as a WAR file.
 *
 * @author graemerocher
 * @since 1.0.0
 */
public class MicronautServletInitializer implements ServletContainerInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautServletInitializer.class);

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) {
        final ApplicationContext applicationContext = buildApplicationContext(ctx)
                .build()
                .start();
        final MicronautServletConfiguration configuration = applicationContext.getBean(MicronautServletConfiguration.class);
        final ServletRegistration.Dynamic registration =
                ctx.addServlet(configuration.getName(), new DefaultMicronautServlet(applicationContext));

        configuration.getMultipartConfigElement().ifPresent(registration::setMultipartConfig);
        applicationContext.findBean(ServletSecurityElement.class)
                .ifPresent(registration::setServletSecurity);
        registration.setLoadOnStartup(1);
        registration.setAsyncSupported(true);
        registration.addMapping(configuration.getMapping());
    }

    /**
     * Builds the application context.
     * @param ctx The context
     * @return The application context builder
     */
    protected ApplicationContextBuilder buildApplicationContext(ServletContext ctx) {
        ApplicationContextBuilder contextBuilder = ApplicationContext
            .builder()
            .classLoader(ctx.getClassLoader())
            .singletons(ctx);

        // If deployed as ROOT.war (to the root context, this will be empty)
        final String servletContextPath = ctx.getContextPath();
        if (!isEmpty(servletContextPath)) {
            // We are loaded into a non-root context. Set the context path as a property.
            if (LOG.isDebugEnabled()) {
                LOG.debug("Setting micronaut context-path to match servlet context path: '{}'", servletContextPath);
            }
            contextBuilder.properties(Map.of("micronaut.server.context-path", servletContextPath));
        }

        return contextBuilder;
    }
}
