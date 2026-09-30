package io.micronaut.servlet.docs.war;

// tag::class[]
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.servlet.engine.initializer.MicronautServletInitializer;
import jakarta.servlet.ServletContext;

public class CustomInitializer extends MicronautServletInitializer {

    @Override
    protected ApplicationContextBuilder buildApplicationContext(ServletContext ctx) {
        return ApplicationContext
                .builder()
                .overrideConfigLocations(
                    "file:/tmp",
                    "classpath:/some/path"
                )
                .classLoader(ctx.getClassLoader())
                .singletons(ctx);
    }
}
// end::class[]
