/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.servlet.websocket.reflection;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.reflection.ReflectionBeanDefinition;
import io.micronaut.reflection.ReflectionIntrospectionPolicy;
import io.micronaut.servlet.websocket.ReflectiveBeanDefinitions;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers Jakarta WebSocket components through {@code micronaut-reflection}, for the types the
 * application allows with {@code micronaut.introspection.allow-reflection}.
 *
 * <p>The same patterns say which types the shared introspector may describe reflectively, so
 * there is one switch for reflection rather than a WebSocket specific one. A class that declares
 * no scope becomes a prototype, which is what the Jakarta container would do: one decoder and
 * encoder per endpoint instance.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
@Singleton
@Requires(classes = ReflectionBeanDefinition.class)
final class MicronautReflectionBeanDefinitions implements ReflectiveBeanDefinitions {

    private final BeanContext beanContext;
    private final Map<Class<?>, Boolean> registered = new ConcurrentHashMap<>();

    MicronautReflectionBeanDefinitions(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public boolean isAllowed(Class<?> type) {
        return ReflectionIntrospectionPolicy.isAllowed(type);
    }

    @Override
    public boolean register(Class<?> type) {
        if (!isAllowed(type) || !ReflectionBeanDefinition.isDefinable(type)) {
            return false;
        }
        return registered.computeIfAbsent(type, t -> {
            ReflectionBeanDefinition.Builder<?> builder = ReflectionBeanDefinition.builder(t);
            if (!declaresScope(t)) {
                builder.scope(Prototype.class);
            }
            beanContext.registerBeanDefinition(builder.build());
            return true;
        });
    }

    private static boolean declaresScope(Class<?> type) {
        for (Annotation annotation : type.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Scope.class)) {
                return true;
            }
        }
        return false;
    }
}
