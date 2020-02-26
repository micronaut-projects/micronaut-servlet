package io.micronaut.servlet.engine;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.core.annotation.TypeHint;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Objects;

/**
 * A servlet that initializes Micronaut and serves requests.
 *
 * @author graemerocher
 * @since 1.0
 */
@TypeHint(DefaultMicronautServlet.class)
public class DefaultMicronautServlet extends HttpServlet {

    private ApplicationContext applicationContext;
    private DefaultServletHttpHandler handler;

    public DefaultMicronautServlet(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public DefaultMicronautServlet() {
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
        if (handler != null) {
            handler.service(
                    req,
                    resp
            );
        }
    }

    @Override
    public void destroy() {
        if (applicationContext != null && applicationContext.isRunning()) {
            applicationContext.stop();
            applicationContext = null;
        }
    }

    @Override
    public void init() {
        if (this.applicationContext == null) {

            final ApplicationContextBuilder builder =
                    Objects.requireNonNull(newApplicationContextBuilder(), "builder cannot be null");
            this.applicationContext = Objects.requireNonNull(buildApplicationContext(builder), "Context cannot be null");
        }

        if (!this.applicationContext.isRunning()) {
            this.applicationContext.start();
        }
        this.handler = applicationContext.getBean(DefaultServletHttpHandler.class);
    }

    /**
     * @param builder The builder
     * @return The built context, must not null.
     */
    protected ApplicationContext buildApplicationContext(ApplicationContextBuilder builder) {
        return builder.build();
    }

    /**
     * @return A new {@link ApplicationContext} builder
     */
    protected ApplicationContextBuilder newApplicationContextBuilder() {
        return ApplicationContext.build();
    }
}
