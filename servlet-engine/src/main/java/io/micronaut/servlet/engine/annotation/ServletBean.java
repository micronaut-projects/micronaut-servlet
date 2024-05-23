/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.servlet.engine.annotation;

import io.micronaut.context.annotation.AliasFor;
import io.micronaut.context.annotation.Bean;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Variant of {@link jakarta.servlet.annotation.WebServlet} applicable to factory methods.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Bean
public @interface ServletBean {
    /**
     * The name of the servlet.
     *
     * @return the name of the servlet
     */
    @AliasFor(annotation = WebServlet.class, member = "name")
    String name();

    /**
     * The URL patterns of the servlet.
     *
     * @return the URL patterns of the servlet
     */
    @AliasFor(annotation = WebServlet.class, member = "value")
    String[] value() default {};

    /**
     * The URL patterns of the servlet.
     *
     * @return the URL patterns of the servlet
     */
    @AliasFor(annotation = WebServlet.class, member = "value")
    String[] urlPatterns() default {};

    /**
     * The load-on-startup order of the servlet.
     *
     * @return the load-on-startup order of the servlet
     */
    @AliasFor(annotation = WebServlet.class, member = "loadOnStartup")
    int loadOnStartup() default -1;

    /**
     * The init parameters of the servlet.
     *
     * @return the init parameters of the servlet
     */
    @AliasFor(annotation = WebServlet.class, member = "initParams")
    WebInitParam[] initParams() default {};

    /**
     * Declares whether the servlet supports asynchronous operation mode.
     *
     * @return {@code true} if the servlet supports asynchronous operation mode
     * @see jakarta.servlet.ServletRequest#startAsync
     * @see jakarta.servlet.ServletRequest#startAsync( jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse)
     */
    @AliasFor(annotation = WebServlet.class, member = "asyncSupported")
    boolean asyncSupported() default false;
}
