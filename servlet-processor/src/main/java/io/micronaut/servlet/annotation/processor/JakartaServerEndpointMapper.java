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
import io.micronaut.core.util.ArrayUtils;

import java.lang.annotation.Annotation;

/**
 * Maps {@code jakarta.websocket.server.ServerEndpoint} to {@code io.micronaut.websocket.annotation.ServerWebSocket}.
 *
 * <p>The URI template syntax is the same in both models, so the path is copied as is. The
 * subprotocols array becomes the comma separated list Micronaut expects.</p>
 *
 * <p>The scope is not decided here: a mapper sees only the annotation, and a scope the class
 * declares itself has to win. {@link JakartaWebSocketVisitor} makes the endpoint a prototype
 * when the class declares no scope, which is the Jakarta contract of one endpoint instance per
 * connection.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
public final class JakartaServerEndpointMapper extends AbstractJakartaWebSocketMapper {

    static final String SUBPROTOCOLS = "subprotocols";

    /**
     * Default constructor.
     */
    public JakartaServerEndpointMapper() {
        super(SERVER_ENDPOINT, SERVER_WEB_SOCKET);
    }

    @Override
    void copyMembers(AnnotationValue<Annotation> source, AnnotationValueBuilder<Annotation> target) {
        source.stringValue().ifPresent(target::value);
        String[] subprotocols = source.stringValues(SUBPROTOCOLS);
        if (ArrayUtils.isNotEmpty(subprotocols)) {
            target.member(SUBPROTOCOLS, String.join(",", subprotocols));
        }
    }
}
