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
package io.micronaut.servlet.websocket;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

/**
 * Registers a class the annotation processors never saw as a bean, reflectively.
 *
 * <p>The one implementation lives behind {@code micronaut-reflection}; without that module on the
 * classpath no bean of this type exists and nothing is ever instantiated reflectively.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
public interface ReflectiveBeanDefinitions {

    /**
     * Whether the application allows the type to be described reflectively.
     *
     * @param type The type
     * @return {@code true} if it may be registered
     */
    boolean isAllowed(Class<?> type);

    /**
     * Registers a definition for the type, if it is allowed and not registered already.
     *
     * @param type The type
     * @return {@code true} if a definition now exists for the type
     */
    boolean register(Class<?> type);

    /**
     * Resolves the type argument a class gives to one of its generic interfaces, from the
     * class's generic signature.
     *
     * @param type          The class
     * @param interfaceType The generic interface
     * @return The type argument, or {@code null} when the class does not implement the interface
     */
    @Nullable Class<?> resolveTypeArgument(Class<?> type, Class<?> interfaceType);
}
