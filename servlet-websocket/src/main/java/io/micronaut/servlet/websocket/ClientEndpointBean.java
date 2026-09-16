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
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.ExecutionHandle;
import io.micronaut.inject.MethodExecutionHandle;
import io.micronaut.websocket.WebSocketPongMessage;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnError;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.context.WebSocketBean;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;

/**
 * A {@code @ClientEndpoint} bean with its lifecycle handlers, as {@link WebSocketBean} describes
 * one.
 *
 * <p>The Micronaut client resolves its bean through the {@code @ClientWebSocket} stereotype,
 * which a Jakarta client endpoint does not carry: it is a plain bean whose handlers the
 * annotation processor mapped. So the handlers are read here from the bean definition instead,
 * the way {@code WebSocketBeanRegistry} reads them, and the message handlers are then classified
 * by category through {@link JakartaEndpoint}.</p>
 *
 * @param target     The endpoint instance the connection is dispatched to
 * @param definition The bean definition
 * @param open       The {@code @OnOpen} handler, if any
 * @param close      The {@code @OnClose} handler, if any
 * @param message    An {@code @OnMessage} handler, if any
 * @param pong       The pong handler, if any
 * @param error      The {@code @OnError} handler, if any
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
record ClientEndpointBean(Object target,
                          BeanDefinition<Object> definition,
                          @Nullable MethodExecutionHandle<Object, ?> open,
                          @Nullable MethodExecutionHandle<Object, ?> close,
                          @Nullable MethodExecutionHandle<Object, ?> message,
                          @Nullable MethodExecutionHandle<Object, ?> pong,
                          @Nullable MethodExecutionHandle<Object, ?> error) implements WebSocketBean<Object> {

    /**
     * Reads the handlers of the endpoint.
     *
     * @param definition The bean definition
     * @param target     The endpoint instance
     * @return The bean
     */
    @SuppressWarnings("unchecked")
    static ClientEndpointBean of(BeanDefinition<Object> definition, Object target) {
        MethodExecutionHandle<Object, ?> open = null;
        MethodExecutionHandle<Object, ?> close = null;
        MethodExecutionHandle<Object, ?> message = null;
        MethodExecutionHandle<Object, ?> pong = null;
        MethodExecutionHandle<Object, ?> error = null;
        for (ExecutableMethod<Object, ?> method : definition.getExecutableMethods()) {
            if (method.isAnnotationPresent(OnOpen.class)) {
                open = ExecutionHandle.of(target, method);
            } else if (method.isAnnotationPresent(OnClose.class)) {
                close = ExecutionHandle.of(target, method);
            } else if (method.isAnnotationPresent(OnError.class)) {
                error = ExecutionHandle.of(target, method);
            } else if (method.isAnnotationPresent(OnMessage.class)) {
                if (Arrays.asList(method.getArgumentTypes()).contains(WebSocketPongMessage.class)) {
                    pong = ExecutionHandle.of(target, method);
                } else {
                    message = ExecutionHandle.of(target, method);
                }
            }
        }
        return new ClientEndpointBean(target, definition, open, close, message, pong, error);
    }

    @Override
    public BeanDefinition<Object> getBeanDefinition() {
        return definition;
    }

    @Override
    public Object getTarget() {
        return target;
    }

    @Override
    public Optional<MethodExecutionHandle<Object, ?>> messageMethod() {
        return Optional.ofNullable(message);
    }

    @Override
    public Optional<MethodExecutionHandle<Object, ?>> pongMethod() {
        return Optional.ofNullable(pong);
    }

    @Override
    public Optional<MethodExecutionHandle<Object, ?>> closeMethod() {
        return Optional.ofNullable(close);
    }

    @Override
    public Optional<MethodExecutionHandle<Object, ?>> openMethod() {
        return Optional.ofNullable(open);
    }

    @Override
    public Optional<MethodExecutionHandle<Object, ?>> errorMethod() {
        return Optional.ofNullable(error);
    }
}
