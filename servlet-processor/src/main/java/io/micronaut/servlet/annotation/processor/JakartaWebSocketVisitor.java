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
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.JAKARTA_ON_CLOSE;
import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.JAKARTA_ON_ERROR;
import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.JAKARTA_ON_MESSAGE;
import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.JAKARTA_ON_OPEN;
import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.PATH_PARAM;
import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.SERVER_ENDPOINT;
import static io.micronaut.servlet.annotation.processor.AbstractJakartaWebSocketMapper.SERVER_WEB_SOCKET;
import static io.micronaut.servlet.annotation.processor.JakartaOnMessageMapper.MAX_MESSAGE_SIZE;

/**
 * Validates a Jakarta WebSocket endpoint at compilation time and settles its scope.
 *
 * <p>The mappers turn the Jakarta annotations into Micronaut ones; this visitor checks what the
 * runtime cannot recover from and reports it against the source instead of the first handshake:
 * a server endpoint has a message handler, an endpoint has at most one handler per category,
 * does not use partial messages, and every class it names in {@code decoders}, {@code encoders}
 * or {@code configurator} can be instantiated. The ones that are not beans get an introspection
 * generated, so no reflection is needed to create them.</p>
 *
 * <p>A {@code @ServerEndpoint} is mapped onto {@code @ServerWebSocket}. A {@code @ClientEndpoint}
 * maps onto no Micronaut annotation: it is a plain bean whose handlers the lifecycle mappers made
 * executable, opened through the {@code WebSocketContainer} bean with the URI given to
 * {@code connectToServer}. A client has no URI template, so {@code @PathParam} is rejected, and
 * no reason to receive, so {@code @OnMessage} is optional.</p>
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
    static final String DEFAULT_SERVER_CONFIGURATOR = "jakarta.websocket.server.ServerEndpointConfig$Configurator";
    static final String DEFAULT_CLIENT_CONFIGURATOR = "jakarta.websocket.ClientEndpointConfig$Configurator";

    static final String CONSUMES = "io.micronaut.http.annotation.Consumes";
    static final String BINDABLE = "io.micronaut.core.bind.annotation.Bindable";
    static final String CLASSES = "classes";
    static final String CLASS_NAMES = "classNames";
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
        AnnotationValue<Annotation> serverEndpoint = element.getDeclaredAnnotation(SERVER_ENDPOINT);
        AnnotationValue<Annotation> clientEndpoint = element.getDeclaredAnnotation(CLIENT_ENDPOINT);
        if (serverEndpoint == null && clientEndpoint == null) {
            return;
        }
        if (serverEndpoint != null && clientEndpoint != null) {
            throw new ProcessingException(element, "An endpoint cannot be both a @ServerEndpoint and a @ClientEndpoint");
        }
        EndpointKind kind = serverEndpoint != null ? EndpointKind.SERVER : EndpointKind.CLIENT;
        AnnotationValue<Annotation> endpoint = serverEndpoint != null ? serverEndpoint : clientEndpoint;
        if (context.getClassElement(SERVER_WEB_SOCKET).isEmpty()) {
            throw new ProcessingException(element, kind.name + " requires the Micronaut WebSocket API. Add io.micronaut.servlet:micronaut-servlet-websocket to the classpath");
        }
        validateHandlers(element, endpoint, kind, context);
        introspectComponents(element, endpoint, kind, context);
        if (!element.hasStereotype(AnnotationUtil.SCOPE)) {
            element.annotate(PROTOTYPE);
        }
    }

    private static void validateHandlers(ClassElement element, AnnotationValue<Annotation> endpoint, EndpointKind kind, VisitorContext context) {
        List<MethodElement> messageHandlers = handlers(element, JAKARTA_ON_MESSAGE);
        if (messageHandlers.isEmpty() && kind == EndpointKind.SERVER) {
            // A client may only send; a server route without a message handler never upgrades.
            throw new ProcessingException(element, kind.name + " must declare at least one @OnMessage method");
        }
        validateMessageHandlers(messageHandlers, endpoint, kind, context);
        for (String annotation : new String[] {JAKARTA_ON_OPEN, JAKARTA_ON_CLOSE, JAKARTA_ON_ERROR}) {
            List<MethodElement> lifecycle = handlers(element, annotation);
            if (lifecycle.size() > 1) {
                throw new ProcessingException(lifecycle.get(1), kind.name + " declares more than one @" + annotation.substring(annotation.lastIndexOf('.') + 1) + " method");
            }
        }
        if (kind == EndpointKind.CLIENT) {
            rejectPathParams(element);
        }
    }

    private static void validateMessageHandlers(List<MethodElement> messageHandlers, AnnotationValue<Annotation> endpoint, EndpointKind kind, VisitorContext context) {
        Set<MessageKind> kinds = new HashSet<>();
        for (MethodElement handler : messageHandlers) {
            long maxMessageSize = handler.longValue(JAKARTA_ON_MESSAGE, MAX_MESSAGE_SIZE).orElse(-1);
            if (maxMessageSize > Integer.MAX_VALUE) {
                throw new ProcessingException(handler, "@OnMessage maxMessageSize must not exceed " + Integer.MAX_VALUE);
            }
            if (isPartial(handler)) {
                throw new ProcessingException(handler, "Partial message handlers are not supported: messages are always delivered whole, so remove the boolean last-part parameter");
            }
            MessageKind messageKind = messageKind(handler, endpoint, context);
            if (!kinds.add(messageKind)) {
                throw new ProcessingException(handler, kind.name + " declares more than one " + messageKind.description + " @OnMessage method");
            }
        }
    }

    /**
     * A client connects to a URI, not a template, so there is nothing a {@code @PathParam} could
     * be bound from (Jakarta WebSocket 4.3).
     */
    private static void rejectPathParams(ClassElement element) {
        for (MethodElement handler : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            for (ParameterElement parameter : handler.getParameters()) {
                if (parameter.hasAnnotation(PATH_PARAM)) {
                    throw new ProcessingException(parameter, "@PathParam is only supported on a @ServerEndpoint: a @ClientEndpoint connects to a URI, not a URI template");
                }
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
            if (isBound(parameter)) {
                continue;
            }
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

    private static MessageKind messageKind(MethodElement handler, AnnotationValue<Annotation> endpoint, VisitorContext context) {
        List<ParameterElement> candidates = new ArrayList<>();
        for (ParameterElement parameter : handler.getParameters()) {
            if (!isBound(parameter)) {
                candidates.add(parameter);
            }
        }
        for (ParameterElement parameter : candidates) {
            MessageKind kind = kindOfPayloadType(parameter.getType());
            if (kind != null) {
                return kind;
            }
        }
        // An object message is decoded; a declared binary decoder for it makes it a binary message.
        // The runtime cannot tell without reflecting over the decoder's generic signature, so the
        // outcome is recorded on the handler as the media type it consumes.
        for (ParameterElement parameter : candidates) {
            if (hasBinaryDecoder(endpoint, parameter.getType(), context)) {
                if (!handler.hasAnnotation(CONSUMES)) {
                    handler.annotate(CONSUMES, builder -> builder.value(APPLICATION_OCTET_STREAM));
                }
                return MessageKind.BINARY;
            }
        }
        return MessageKind.TEXT;
    }

    /**
     * The category a payload type of the specification decides on its own, or {@code null} for an
     * object type that is decoded.
     */
    private static @Nullable MessageKind kindOfPayloadType(ClassElement type) {
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
        return null;
    }

    /**
     * A parameter bound from the handshake - {@code @PathParam}, a header, a query value - is
     * never the message, whatever its type.
     */
    private static boolean isBound(ParameterElement parameter) {
        return parameter.hasStereotype(BINDABLE) || parameter.hasAnnotation(PATH_PARAM);
    }

    private static boolean isByteArray(ClassElement type) {
        return type.isArray() && "byte".equals(type.fromArray().getName());
    }

    private static boolean hasBinaryDecoder(AnnotationValue<Annotation> endpoint, ClassElement messageType, VisitorContext context) {
        for (AnnotationClassValue<?> decoder : endpoint.annotationClassValues("decoders")) {
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
     * Makes every class named in {@code decoders}, {@code encoders} and {@code configurator}
     * instantiable without reflection.
     *
     * <p>A Jakarta container creates these with {@code Class.newInstance()}. Here a class that is
     * not a bean is listed in {@code @Introspected(classNames = ...)} on the endpoint, so an
     * introspection is generated for it and the runtime instantiates it through that. A class
     * with a public no-argument constructor - the Jakarta contract - therefore needs nothing else.
     * One whose constructor takes arguments has to be a bean, or be built reflectively through
     * {@code micronaut-reflection}, which the runtime falls back to when the module is present.</p>
     */
    private static void introspectComponents(ClassElement element, AnnotationValue<Annotation> endpoint, EndpointKind kind, VisitorContext context) {
        Set<String> introspect = componentsToIntrospect(element, endpoint, kind, context);
        if (introspect.isEmpty()) {
            return;
        }
        AnnotationValue<Annotation> existing = element.getAnnotation(INTROSPECTED);
        if (existing != null) {
            for (AnnotationClassValue<?> listed : existing.annotationClassValues(CLASSES)) {
                introspect.remove(listed.getName());
            }
            String[] listedNames = existing.stringValues(CLASS_NAMES);
            introspect.addAll(List.of(listedNames));
            if (existing.annotationClassValues(CLASSES).length == 0 && listedNames.length == 0) {
                // The endpoint was introspected itself; listing other classes would stop that.
                introspect.add(element.getName());
            }
        }
        element.annotate(INTROSPECTED, builder -> builder.member(CLASS_NAMES, introspect.toArray(String[]::new)));
    }

    /**
     * The declared components that are not beans, checked to be instantiable: through the
     * introspection that will be generated when they have a no-argument constructor, else
     * reflectively when {@code micronaut-reflection} is available.
     */
    private static Set<String> componentsToIntrospect(ClassElement element, AnnotationValue<Annotation> endpoint, EndpointKind kind, VisitorContext context) {
        boolean reflectionAvailable = context.getClassElement(REFLECTION_BEAN_DEFINITION).isPresent();
        Set<String> introspect = new LinkedHashSet<>();
        for (String member : COMPONENT_MEMBERS) {
            for (AnnotationClassValue<?> classValue : endpoint.annotationClassValues(member)) {
                String name = classValue.getName();
                ClassElement component = isDefaultConfigurator(name)
                    ? null
                    : context.getClassElement(name)
                        .orElseThrow(() -> new ProcessingException(element, kind.name + " " + member + " names a class that cannot be resolved: " + name));
                if (component == null || isBean(component)) {
                    continue;
                }
                if (!reflectionAvailable && !hasDefaultConstructor(component)) {
                    throw new ProcessingException(element, kind.name + " " + member + " names a class the runtime cannot instantiate: " + name
                        + ". Give it a public no-argument constructor, make it a bean (@Singleton or @Prototype), "
                        + "or add io.micronaut:micronaut-reflection and allow the type through micronaut.introspection.allow-reflection");
                }
                introspect.add(name);
            }
        }
        return introspect;
    }

    private static boolean isDefaultConfigurator(String name) {
        return isDefaultConfigurator(name, DEFAULT_SERVER_CONFIGURATOR) || isDefaultConfigurator(name, DEFAULT_CLIENT_CONFIGURATOR);
    }

    private static boolean isDefaultConfigurator(String name, String defaultConfigurator) {
        return defaultConfigurator.equals(name) || defaultConfigurator.replace('$', '.').equals(name);
    }

    private static boolean isBean(ClassElement component) {
        return component.hasStereotype(AnnotationUtil.SCOPE) || component.hasStereotype(BEAN);
    }

    private static boolean hasDefaultConstructor(ClassElement component) {
        return !component.isAbstract()
            && component.getDefaultConstructor().filter(constructor -> !constructor.isPrivate()).isPresent();
    }

    /**
     * The two sides an endpoint can be on, with the annotation naming it in messages.
     */
    private enum EndpointKind {
        SERVER("@ServerEndpoint"),
        CLIENT("@ClientEndpoint");

        private final String name;

        EndpointKind(String name) {
            this.name = name;
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
