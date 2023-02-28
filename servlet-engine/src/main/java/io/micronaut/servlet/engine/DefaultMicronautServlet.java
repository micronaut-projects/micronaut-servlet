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
package io.micronaut.servlet.engine;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.TypeHint;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;

/**
 * A servlet that initializes Micronaut and serves requests.
 *
 * @author graemerocher
 * @since 1.0
 */
@TypeHint(DefaultMicronautServlet.class)
public class DefaultMicronautServlet extends HttpServlet {
    /**
     * The name of the servlet.
     */
    public static final String NAME = Environment.MICRONAUT;
    /**
     * Attribute used to store the application context.
     */
    public static final String CONTEXT_ATTRIBUTE = "io.micronaut.servlet.APPLICATION_CONTEXT";

    private ApplicationContext applicationContext;
    private boolean isContextOwner;
    private DefaultServletHttpHandler handler;

    /**
     * Constructor that takes an application context.
     * @param applicationContext The application context.
     */
    public DefaultMicronautServlet(ApplicationContext applicationContext) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "The application context cannot be null");
    }

    /**
     * Default constructor.
     */
    public DefaultMicronautServlet() {
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
        if (handler != null) {
            handler.service(req, resp);
        }
    }

    @Override
    public void destroy() {
        if (isContextOwner && applicationContext != null && applicationContext.isRunning()) {
            applicationContext.stop();
            applicationContext = null;
        }
    }

    @Override
    public void init() {
        final ServletContext servletContext = getServletContext();
        if (servletContext != null) {
            final Object v = servletContext.getAttribute(CONTEXT_ATTRIBUTE);
            if (v instanceof ApplicationContext) {
                this.applicationContext = (ApplicationContext) v;
            }
        }
        if (this.applicationContext == null) {
            final ApplicationContextBuilder builder =
                Objects.requireNonNull(newApplicationContextBuilder(), "builder cannot be null");
            this.applicationContext = Objects.requireNonNull(buildApplicationContext(builder), "Context cannot be null");
            if (!this.applicationContext.isRunning()) {
                this.applicationContext.start();
            }
            isContextOwner = true;
        }
        if (servletContext != null) {
            servletContext.setAttribute(CONTEXT_ATTRIBUTE, applicationContext);
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
        return ApplicationContext.builder();
    }
}
