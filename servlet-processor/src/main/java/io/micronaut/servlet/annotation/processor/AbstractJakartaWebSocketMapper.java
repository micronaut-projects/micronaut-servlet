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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Maps one Jakarta WebSocket annotation onto its Micronaut WebSocket counterpart.
 *
 * <p>The Jakarta and Micronaut annotations are referred to by name only, so this processor works
 * without either API on its classpath: a project that uses it for the Servlet annotations alone is
 * never asked to resolve a WebSocket class.</p>
 *
 * <p>A mapper adds to what it reads, so the Jakarta annotation stays in the metadata next to the
 * Micronaut one. The runtime relies on that to tell a Jakarta endpoint from a Micronaut one.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
abstract class AbstractJakartaWebSocketMapper implements NamedAnnotationMapper {

    static final String SERVER_ENDPOINT = "jakarta.websocket.server.ServerEndpoint";
    static final String JAKARTA_ON_OPEN = "jakarta.websocket.OnOpen";
    static final String JAKARTA_ON_MESSAGE = "jakarta.websocket.OnMessage";
    static final String JAKARTA_ON_CLOSE = "jakarta.websocket.OnClose";
    static final String JAKARTA_ON_ERROR = "jakarta.websocket.OnError";
    static final String PATH_PARAM = "jakarta.websocket.server.PathParam";

    static final String SERVER_WEB_SOCKET = "io.micronaut.websocket.annotation.ServerWebSocket";
    static final String ON_OPEN = "io.micronaut.websocket.annotation.OnOpen";
    static final String ON_MESSAGE = "io.micronaut.websocket.annotation.OnMessage";
    static final String ON_CLOSE = "io.micronaut.websocket.annotation.OnClose";
    static final String ON_ERROR = "io.micronaut.websocket.annotation.OnError";
    static final String PATH_VARIABLE = "io.micronaut.http.annotation.PathVariable";

    private final String source;
    private final String target;

    /**
     * @param source The Jakarta annotation name
     * @param target The Micronaut annotation name
     */
    AbstractJakartaWebSocketMapper(String source, String target) {
        this.source = source;
        this.target = target;
    }

    @Override
    public final String getName() {
        return source;
    }

    @Override
    public final List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        AnnotationValueBuilder<Annotation> builder = AnnotationValue.builder(target);
        copyMembers(annotation, builder);
        return List.of(builder.build());
    }

    /**
     * Copies the members that have a counterpart. Nothing by default.
     *
     * @param source The Jakarta annotation
     * @param target The Micronaut annotation being built
     */
    void copyMembers(AnnotationValue<Annotation> source, AnnotationValueBuilder<Annotation> target) {
    }
}
