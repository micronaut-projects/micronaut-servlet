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

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.websocket.exceptions.WebSocketException;
import jakarta.inject.Singleton;
import jakarta.websocket.Encoder;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Instantiates the classes a {@code @ServerEndpoint} names in {@code decoders}, {@code encoders}
 * and {@code configurator}.
 *
 * <p>A Jakarta container creates these with {@code Class.newInstance()}. Here a class is resolved
 * through the first route that can produce it, and only the last one reflects:</p>
 * <ol>
 *     <li>as a bean, when it is one;</li>
 *     <li>through its introspection, which the annotation processor generates for every class the
 *     endpoint names, when it has a no-argument constructor;</li>
 *     <li>reflectively, when {@code micronaut-reflection} is on the classpath and the type matches
 *     a {@code micronaut.introspection.allow-reflection} pattern, after which it is a bean whose
 *     constructor arguments are injected.</li>
 * </ol>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
@Singleton
public final class JakartaEndpointComponents {

    private static final List<Class<?>> ENCODER_INTERFACES = List.of(
        Encoder.Text.class, Encoder.Binary.class, Encoder.TextStream.class, Encoder.BinaryStream.class
    );

    private final BeanContext beanContext;
    private final @Nullable ReflectiveBeanDefinitions reflective;

    /**
     * Default constructor.
     *
     * @param beanContext The bean context
     * @param reflective  The reflective route, present only with {@code micronaut-reflection}
     */
    public JakartaEndpointComponents(BeanContext beanContext, @Nullable ReflectiveBeanDefinitions reflective) {
        this.beanContext = beanContext;
        this.reflective = reflective;
    }

    /**
     * Creates an instance of the component. A bean is created as its scope says; an introspected
     * type is instantiated anew on every call.
     *
     * @param type The component type
     * @param <T>  The component type
     * @return The instance
     * @throws WebSocketException if no route can produce the type
     */
    public <T> T instantiate(Class<T> type) {
        if (beanContext.containsBean(type)) {
            return beanContext.getBean(type);
        }
        BeanIntrospection<T> introspection = BeanIntrospector.SHARED.findIntrospection(type).orElse(null);
        if (introspection != null && introspection.getConstructorArguments().length == 0) {
            return introspection.instantiate();
        }
        if (reflective != null && reflective.register(type)) {
            return beanContext.getBean(type);
        }
        throw new WebSocketException("Cannot instantiate WebSocket component [" + type.getName() + "] without reflection. "
            + "Give it a public no-argument constructor, make it a bean (@Singleton or @Prototype), "
            + "or add io.micronaut:micronaut-reflection and allow the type through micronaut.introspection.allow-reflection");
    }

    /**
     * The type an encoder encodes: the type argument of the {@code Encoder} interface it
     * implements.
     *
     * <p>Read from the bean definition when the encoder is a bean, which the processors recorded
     * at compilation time. Reading it from the class's generic signature is reflection, so that
     * only happens with {@code micronaut-reflection} on the classpath. Otherwise the type is
     * unknown and the encoder is offered every value, stepping aside for one it cannot encode;
     * an encoder is therefore best declared as a bean.</p>
     *
     * @param encoder The encoder class
     * @return The encoded type, or {@code null} when it cannot be resolved
     */
    public @Nullable Class<?> encodedType(Class<?> encoder) {
        BeanDefinition<?> definition = beanContext.findBeanDefinition(encoder).orElse(null);
        for (Class<?> encoderInterface : ENCODER_INTERFACES) {
            if (definition != null) {
                List<Argument<?>> arguments = definition.getTypeArguments(encoderInterface);
                if (!arguments.isEmpty()) {
                    return arguments.get(0).getType();
                }
            }
            if (reflective != null) {
                Class<?> resolved = reflective.resolveTypeArgument(encoder, encoderInterface);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    /**
     * Whether the container itself may instantiate the type reflectively, which is what it does
     * with the encoders of a {@code ServerEndpointConfig}.
     *
     * @param type The type
     * @return {@code true} when the application has opted the type into reflection
     */
    public boolean isReflectionAllowed(Class<?> type) {
        return reflective != null && reflective.isAllowed(type);
    }
}
