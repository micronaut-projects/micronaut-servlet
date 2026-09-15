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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * What a {@code @ServerEndpoint} declares, read once from its bean definition.
 *
 * <p>The Jakarta model allows one message handler per category - text, binary and pong - where
 * Micronaut has one that converts, so the handlers are classified here by the type of the message
 * parameter, the way the compile time visitor classifies them, and the endpoint dispatches to the
 * matching one. An object message counts as binary when a declared {@code Decoder.Binary} or
 * {@code Decoder.BinaryStream} decodes its type, which the visitor records on the handler as
 * {@code @Consumes(APPLICATION_OCTET_STREAM)}.</p>
 *
 * @param textMethod   The text message handler, if any
 * @param binaryMethod The binary message handler, if any
 * @param pongMethod   The pong handler, if any
 * @param decoders     The declared decoder classes
 * @param encoders     The declared encoder classes
 * @param configurator The declared configurator class, or {@code null} for the default
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
public record JakartaEndpoint(@Nullable ExecutableMethod<Object, ?> textMethod,
                              @Nullable ExecutableMethod<Object, ?> binaryMethod,
                              @Nullable ExecutableMethod<Object, ?> pongMethod,
                              List<Class<?>> decoders,
                              List<Class<?>> encoders,
                              @Nullable Class<?> configurator) {

    /**
     * The Jakarta annotations, by name so that the API is not needed to load this class.
     */
    public static final String SERVER_ENDPOINT = "jakarta.websocket.server.ServerEndpoint";
    public static final String ON_MESSAGE = "jakarta.websocket.OnMessage";

    private static final String PONG_MESSAGE = "jakarta.websocket.PongMessage";
    private static final String DEFAULT_CONFIGURATOR = "jakarta.websocket.server.ServerEndpointConfig$Configurator";

    /**
     * Reads the endpoint from its bean definition.
     *
     * @param definition The bean definition
     * @return The endpoint, or {@code null} when the bean is a Micronaut {@code @ServerWebSocket}
     */
    @SuppressWarnings("unchecked")
    public static @Nullable JakartaEndpoint of(BeanDefinition<?> definition) {
        AnnotationValue<Annotation> serverEndpoint = definition.getAnnotation(SERVER_ENDPOINT);
        if (serverEndpoint == null) {
            return null;
        }
        List<Class<?>> decoders = List.of(serverEndpoint.classValues("decoders"));
        List<Class<?>> encoders = List.of(serverEndpoint.classValues("encoders"));
        Class<?> configurator = serverEndpoint.classValue("configurator")
            .filter(type -> !DEFAULT_CONFIGURATOR.equals(type.getName()))
            .orElse(null);
        ExecutableMethod<Object, ?> text = null;
        ExecutableMethod<Object, ?> binary = null;
        ExecutableMethod<Object, ?> pong = null;
        for (ExecutableMethod<?, ?> method : definition.getExecutableMethods()) {
            if (!method.hasAnnotation(ON_MESSAGE)) {
                continue;
            }
            ExecutableMethod<Object, ?> handler = (ExecutableMethod<Object, ?>) method;
            switch (classify(handler)) {
                case TEXT -> text = handler;
                case BINARY -> binary = handler;
                case PONG -> pong = handler;
                default -> throw new IllegalStateException();
            }
        }
        return new JakartaEndpoint(text, binary, pong, decoders, encoders, configurator);
    }

    /**
     * The message body parameter of a handler: the one that is neither a Jakarta nor a Micronaut
     * framework type. The compile time visitor has already checked there is such a parameter.
     *
     * @param handler The handler
     * @return The message parameter, or {@code null} if the handler declares none
     */
    static @Nullable Argument<?> messageArgument(ExecutableMethod<?, ?> handler) {
        for (Argument<?> argument : handler.getArguments()) {
            if (isMessageType(argument.getType())) {
                return argument;
            }
        }
        return null;
    }

    private static Kind classify(ExecutableMethod<?, ?> handler) {
        for (Argument<?> argument : handler.getArguments()) {
            Class<?> type = argument.getType();
            String name = type.getName();
            if (PONG_MESSAGE.equals(name)) {
                return Kind.PONG;
            }
            if (isBinaryType(type)) {
                return Kind.BINARY;
            }
            if (isTextType(type)) {
                return Kind.TEXT;
            }
        }
        // An object message is decoded. The compile time visitor marks the handler of one that a
        // binary decoder decodes with @Consumes(APPLICATION_OCTET_STREAM), so this needs no
        // reflection over the decoder's generic signature.
        boolean binary = handler.stringValue(Consumes.class)
            .map(MediaType.APPLICATION_OCTET_STREAM::equals)
            .orElse(false);
        return binary ? Kind.BINARY : Kind.TEXT;
    }

    static boolean isBinaryType(Class<?> type) {
        return type == ByteBuffer.class || type == byte[].class || type == InputStream.class;
    }

    static boolean isTextType(Class<?> type) {
        return type == String.class || type == Reader.class || type.isPrimitive() || isBoxedPrimitive(type);
    }

    private static boolean isBoxedPrimitive(Class<?> type) {
        return type == Boolean.class || type == Character.class || type == Byte.class || type == Short.class
            || type == Integer.class || type == Long.class || type == Float.class || type == Double.class;
    }

    private static boolean isMessageType(Class<?> type) {
        String name = type.getName();
        return !name.startsWith("jakarta.websocket.") && !name.startsWith("io.micronaut.");
    }

    private enum Kind {
        TEXT, BINARY, PONG
    }
}
