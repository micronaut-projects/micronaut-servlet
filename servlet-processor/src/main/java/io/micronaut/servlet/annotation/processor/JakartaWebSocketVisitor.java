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
package io.micronaut.servlet.annotation.processor;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.JAKARTA_ON_CLOSE;
import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.JAKARTA_ON_ERROR;
import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.JAKARTA_ON_MESSAGE;
import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.JAKARTA_ON_OPEN;
import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.SERVER_ENDPOINT;
import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.SERVER_WEB_SOCKET;
import static io.micronaut.servlet.annotation.processor.JakartaOnMessageMapper.MAX_MESSAGE_SIZE;

/**
 * Validates a Jakarta WebSocket endpoint at compilation time and settles its scope.
 *
 * <p>The mappers turn the Jakarta annotations into Micronaut ones; this visitor checks what the
 * runtime cannot recover from and reports it against the source instead of the first handshake:
 * the endpoint has a message handler, has at most one handler per category, does not use partial
 * messages, and every class it names in {@code decoders}, {@code encoders} or {@code configurator}
 * can be instantiated by one of the routes the runtime resolves them through.</p>
 *
 * <p>An endpoint that declares no scope of its own is made a {@code @Prototype}, which is the
 * Jakarta contract of one endpoint instance per connection. A declared scope wins.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
public final class JakartaWebSocketVisitor implements TypeElementVisitor<Object, Object> {

    static final String CLIENT_ENDPOINT = "jakarta.websocket.ClientEndpoint";
    static final String PROTOTYPE = "io.micronaut.context.annotation.Prototype";
    static final String INTROSPECTED = "io.micronaut.core.annotation.Introspected";
    static final String BEAN = "io.micronaut.context.annotation.Bean";
    static final String REFLECTION_BEAN_DEFINITION = "io.micronaut.reflection.ReflectionBeanDefinition";
    static final String DEFAULT_CONFIGURATOR = "jakarta.websocket.server.ServerEndpointConfig$Configurator";

    static final String CONSUMES = "io.micronaut.http.annotation.Consumes";
    static final String APPLICATION_OCTET_STREAM = "application/octet-stream";

    private static final String PONG_MESSAGE = "jakarta.websocket.PongMessage";
    private static final String DECODER_BINARY = "jakarta.websocket.Decoder$Binary";
    private static final String DECODER_BINARY_STREAM = "jakarta.websocket.Decoder$BinaryStream";
    private static final Set<String> TEXT_TYPES = Set.of(
        String.class.getName(), "java.io.Reader",
        Boolean.class.getName(), Character.class.getName(), Byte.class.getName(), Short.class.getName(),
        Integer.class.getName(), Long.class.getName(), Float.class.getName(), Double.class.getName()
    );
    private static final Set<String> BINARY_TYPES = Set.of("java.nio.ByteBuffer", "java.io.InputStream");
    private static final Set<String> PARTIAL_MESSAGE_TYPES = Set.of(String.class.getName(), "java.nio.ByteBuffer");
    private static final String[] COMPONENT_MEMBERS = {"decoders", "encoders", "configurator"};

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of("jakarta.websocket.*");
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.hasDeclaredAnnotation(CLIENT_ENDPOINT)) {
            throw new ProcessingException(element, "@ClientEndpoint is not supported. Use io.micronaut.websocket.annotation.ClientWebSocket instead");
        }
        AnnotationValue<Annotation> serverEndpoint = element.getDeclaredAnnotation(SERVER_ENDPOINT);
        if (serverEndpoint == null) {
            return;
        }
        if (context.getClassElement(SERVER_WEB_SOCKET).isEmpty()) {
            throw new ProcessingException(element, "@ServerEndpoint requires the Micronaut WebSocket API. Add io.micronaut.servlet:micronaut-servlet-websocket to the classpath");
        }
        validateHandlers(element, serverEndpoint, context);
        validateComponents(element, serverEndpoint, context);
        if (!element.hasStereotype(AnnotationUtil.SCOPE)) {
            element.annotate(PROTOTYPE);
        }
    }

    private static void validateHandlers(ClassElement element, AnnotationValue<Annotation> serverEndpoint, VisitorContext context) {
        List<MethodElement> messageHandlers = handlers(element, JAKARTA_ON_MESSAGE);
        if (messageHandlers.isEmpty()) {
            throw new ProcessingException(element, "@ServerEndpoint must declare at least one @OnMessage method");
        }
        Set<MessageKind> kinds = new HashSet<>();
        for (MethodElement handler : messageHandlers) {
            long maxMessageSize = handler.longValue(JAKARTA_ON_MESSAGE, MAX_MESSAGE_SIZE).orElse(-1);
            if (maxMessageSize > Integer.MAX_VALUE) {
                throw new ProcessingException(handler, "@OnMessage maxMessageSize must not exceed " + Integer.MAX_VALUE);
            }
            if (isPartial(handler)) {
                throw new ProcessingException(handler, "Partial message handlers are not supported: messages are always delivered whole, so remove the boolean last-part parameter");
            }
            MessageKind kind = messageKind(handler, serverEndpoint, context);
            if (!kinds.add(kind)) {
                throw new ProcessingException(handler, "@ServerEndpoint declares more than one " + kind.description + " @OnMessage method");
            }
        }
        for (String annotation : new String[] {JAKARTA_ON_OPEN, JAKARTA_ON_CLOSE, JAKARTA_ON_ERROR}) {
            List<MethodElement> lifecycle = handlers(element, annotation);
            if (lifecycle.size() > 1) {
                throw new ProcessingException(lifecycle.get(1), "@ServerEndpoint declares more than one @" + annotation.substring(annotation.lastIndexOf('.') + 1) + " method");
            }
        }
    }

    private static List<MethodElement> handlers(ClassElement element, String annotation) {
        return element.getEnclosedElements(ElementQuery.ALL_METHODS.annotated(metadata -> metadata.hasAnnotation(annotation)));
    }

    /**
     * A partial message handler takes the message and a {@code boolean} saying whether it is the
     * last part (Jakarta WebSocket 4.7).
     */
    private static boolean isPartial(MethodElement handler) {
        boolean message = false;
        boolean flag = false;
        for (ParameterElement parameter : handler.getParameters()) {
            ClassElement type = parameter.getType();
            String name = type.getName();
            if (PARTIAL_MESSAGE_TYPES.contains(name) || isByteArray(type)) {
                message = true;
            } else if ("boolean".equals(name) || Boolean.class.getName().equals(name)) {
                flag = true;
            }
        }
        return message && flag;
    }

    private static MessageKind messageKind(MethodElement handler, AnnotationValue<Annotation> serverEndpoint, VisitorContext context) {
        for (ParameterElement parameter : handler.getParameters()) {
            ClassElement type = parameter.getType();
            String name = type.getName();
            if (PONG_MESSAGE.equals(name)) {
                return MessageKind.PONG;
            }
            if (BINARY_TYPES.contains(name) || isByteArray(type)) {
                return MessageKind.BINARY;
            }
            if (type.isPrimitive() || TEXT_TYPES.contains(name)) {
                return MessageKind.TEXT;
            }
        }
        // An object message is decoded; a declared binary decoder for it makes it a binary message.
        // The runtime cannot tell without reflecting over the decoder's generic signature, so the
        // outcome is recorded on the handler as the media type it consumes.
        for (ParameterElement parameter : handler.getParameters()) {
            if (hasBinaryDecoder(serverEndpoint, parameter.getType(), context)) {
                if (!handler.hasAnnotation(CONSUMES)) {
                    handler.annotate(CONSUMES, builder -> builder.value(APPLICATION_OCTET_STREAM));
                }
                return MessageKind.BINARY;
            }
        }
        return MessageKind.TEXT;
    }

    private static boolean isByteArray(ClassElement type) {
        return type.isArray() && "byte".equals(type.fromArray().getName());
    }

    private static boolean hasBinaryDecoder(AnnotationValue<Annotation> serverEndpoint, ClassElement messageType, VisitorContext context) {
        for (AnnotationClassValue<?> decoder : serverEndpoint.annotationClassValues("decoders")) {
            ClassElement decoderType = context.getClassElement(decoder.getName()).orElse(null);
            if (decoderType == null) {
                continue;
            }
            if (decoderType.isAssignable(DECODER_BINARY) || decoderType.isAssignable(DECODER_BINARY_STREAM)) {
                boolean decodesMessage = decoderType.getAllTypeArguments().values().stream()
                    .flatMap(arguments -> arguments.values().stream())
                    .anyMatch(argument -> argument.getName().equals(messageType.getName()));
                if (decodesMessage) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Every class named in {@code decoders}, {@code encoders} and {@code configurator} has to be
     * resolvable by the runtime: as a bean, through an introspection, or reflectively when
     * {@code micronaut-reflection} is available and allows the type.
     */
    private static void validateComponents(ClassElement element, AnnotationValue<Annotation> serverEndpoint, VisitorContext context) {
        Set<String> introspected = new HashSet<>();
        AnnotationValue<Annotation> introspectedAnnotation = element.getAnnotation(INTROSPECTED);
        if (introspectedAnnotation != null) {
            for (AnnotationClassValue<?> classValue : introspectedAnnotation.annotationClassValues("classes")) {
                introspected.add(classValue.getName());
            }
        }
        boolean reflectionAvailable = context.getClassElement(REFLECTION_BEAN_DEFINITION).isPresent();
        List<String> unresolvable = new ArrayList<>();
        for (String member : COMPONENT_MEMBERS) {
            for (AnnotationClassValue<?> classValue : serverEndpoint.annotationClassValues(member)) {
                String name = classValue.getName();
                if (DEFAULT_CONFIGURATOR.equals(name) || DEFAULT_CONFIGURATOR.replace('$', '.').equals(name)) {
                    continue;
                }
                if (reflectionAvailable || introspected.contains(name)) {
                    continue;
                }
                ClassElement component = context.getClassElement(name).orElse(null);
                if (component == null) {
                    throw new ProcessingException(element, "@ServerEndpoint " + member + " names a class that cannot be resolved: " + name);
                }
                if (!component.hasStereotype(AnnotationUtil.SCOPE)
                    && !component.hasStereotype(BEAN)
                    && !component.hasAnnotation(INTROSPECTED)) {
                    unresolvable.add(name);
                }
            }
        }
        if (!unresolvable.isEmpty()) {
            throw new ProcessingException(element, "@ServerEndpoint names classes the runtime cannot instantiate without reflection: "
                + String.join(", ", unresolvable)
                + ". Make each one a bean (@Singleton or @Prototype), add @Introspected to it or list it in @Introspected(classes = ...) on the endpoint, "
                + "or add io.micronaut:micronaut-reflection and allow the type through micronaut.introspection.allow-reflection");
        }
    }

    /**
     * The categories Jakarta WebSocket 4.7 allows one handler for each.
     */
    private enum MessageKind {
        TEXT("text"),
        BINARY("binary"),
        PONG("pong");

        private final String description;

        MessageKind(String description) {
            this.description = description;
        }
    }
}
