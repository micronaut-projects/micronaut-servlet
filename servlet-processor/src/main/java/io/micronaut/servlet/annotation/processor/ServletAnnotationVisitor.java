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
package io.micronaut.servlet.annotation.processor;

import static io.micronaut.core.util.ArrayUtils.concat;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import java.util.Set;

public class ServletAnnotationVisitor implements TypeElementVisitor<Object, Object> {
    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of("jakarta.servlet.*");
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.hasDeclaredAnnotation(WebFilter.class) && !element.isAssignable(Filter.class)) {
            throw new ProcessingException(element, "Types annotated with @WebFilter must implement jakarta.servlet.Filter");
        }
        if (element.hasDeclaredAnnotation(WebServlet.class) && !element.isAssignable(Servlet.class)) {
            throw new ProcessingException(element, "Types annotated with @WebServlet must implement jakarta.servlet.Servlet");
        }
        if (element.hasDeclaredAnnotation(WebListener.class) && !element.isAssignable(java.util.EventListener.class)) {
            throw new ProcessingException(element, "Types annotated with @WebListener must implement java.util.EventListener");
        }

        @NonNull String[] patterns = concat(concat(
                element.stringValues(WebFilter.class),
                element.stringValues(WebServlet.class, "urlPatterns")
            ),
            concat(
                element.stringValues(WebServlet.class),
                element.stringValues(WebServlet.class, "urlPatterns")
            ));
        for (String pattern : patterns) {
            if (pattern.endsWith("/**") && pattern.length() > 3) {
                throw new ProcessingException(element, "Servlet Spec 12.2 violation: glob '*' can only exist at end of prefix based matches: bad spec \"" + pattern + "\"");
            }
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (element.hasDeclaredAnnotation(WebFilter.class) && !element.getGenericReturnType().isAssignable(Filter.class)) {
            throw new ProcessingException(element, "Methods annotated with @ServletFilterBean must implement jakarta.servlet.Filter");
        }
        if (element.hasDeclaredAnnotation(WebServlet.class) && !element.getGenericReturnType().isAssignable(Servlet.class)) {
            throw new ProcessingException(element, "Methods annotated with @ServletBean must implement jakarta.servlet.Servlet");
        }
    }
}
