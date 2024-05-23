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
import io.micronaut.core.annotation.AnnotationMetadata;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.annotation.WebFilter;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Variant of {@link jakarta.servlet.annotation.WebFilter} applicable to factory methods.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Bean
public @interface ServletFilterBean {
    /**
     * The name of the filter.
     *
     * @return the name of the filter
     */
    @AliasFor(annotation = WebFilter.class, member = "filterName")
    String filterName();

    /**
     * The names of the servlets to which the filter applies.
     *
     * @return the names of the servlets to which the filter applies
     */
    @AliasFor(annotation = WebFilter.class, member = "servletNames")
    String[] servletNames() default {};

    /**
     * The URL patterns to which the filter applies The default value is an empty array.
     *
     * @return the URL patterns to which the filter applies
     */
    @AliasFor(annotation = WebFilter.class, member = AnnotationMetadata.VALUE_MEMBER)
    String[] value() default {};

    /**
     * The URL patterns to which the filter applies.
     *
     * @return the URL patterns to which the filter applies
     */
    @AliasFor(annotation = WebFilter.class, member = AnnotationMetadata.VALUE_MEMBER)
    String[] urlPatterns() default {};

    /**
     * The dispatcher types to which the filter applies.
     *
     * @return the dispatcher types to which the filter applies
     */
    @AliasFor(annotation = WebFilter.class, member = "dispatcherTypes")
    DispatcherType[] dispatcherTypes() default { DispatcherType.REQUEST };

    /**
     * Declares whether the filter supports asynchronous operation mode.
     *
     * @return {@code true} if the filter supports asynchronous operation mode
     * @see jakarta.servlet.ServletRequest#startAsync
     * @see jakarta.servlet.ServletRequest#startAsync( jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse)
     */
    @AliasFor(annotation = WebFilter.class, member = "asyncSupported")
    boolean asyncSupported() default false;

}
