package io.micronaut.servlet.engine.initializer;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.servlet.engine.DefaultMicronautServlet;

import javax.servlet.*;
import java.util.Set;

/**
 * A servlet initializer for Micronaut for deployment as a WAR file.
 *
 * @author graemerocher
 * @since 1.0.0
 */
public class MicronautServletInitializer implements ServletContainerInitializer {
    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) {
        final ApplicationContext applicationContext = buildApplicationContext(ctx)
                .build()
                .start();
        final ServletRegistration.Dynamic registration =
                ctx.addServlet(DefaultMicronautServlet.NAME, new DefaultMicronautServlet(applicationContext));

        applicationContext.findBean(MultipartConfigElement.class)
                .ifPresent(registration::setMultipartConfig);
        applicationContext.findBean(ServletSecurityElement.class)
                .ifPresent(registration::setServletSecurity);
        registration.setLoadOnStartup(1);
        registration.setAsyncSupported(true);
        registration.addMapping("/*");
    }

    /**
     * Builds the application context.
     * @param ctx The context
     * @return The applicaiton context builder
     */
    protected ApplicationContextBuilder buildApplicationContext(ServletContext ctx) {
        return ApplicationContext
                .build()
                .classLoader(ctx.getClassLoader())
                .singletons(ctx);
    }
}
