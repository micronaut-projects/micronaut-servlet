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

import java.lang.annotation.Annotation;

/**
 * Maps {@code jakarta.websocket.OnMessage} to {@code io.micronaut.websocket.annotation.OnMessage}.
 *
 * <p>{@code maxMessageSize} becomes {@code maxPayloadLength}, but only when the endpoint declared
 * it: the Jakarta default of {@code -1} means "the container's default", and the runtime treats
 * a {@code maxPayloadLength} that is present as declared, so copying the default would silently
 * override the configured message size.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
public final class JakartaOnMessageMapper extends AbstractJakartaWebSocketMapper {

    static final String MAX_MESSAGE_SIZE = "maxMessageSize";
    static final String MAX_PAYLOAD_LENGTH = "maxPayloadLength";

    /**
     * Default constructor.
     */
    public JakartaOnMessageMapper() {
        super(JAKARTA_ON_MESSAGE, ON_MESSAGE);
    }

    @Override
    void copyMembers(AnnotationValue<Annotation> source, AnnotationValueBuilder<Annotation> target) {
        long maxMessageSize = source.longValue(MAX_MESSAGE_SIZE).orElse(-1);
        if (maxMessageSize > 0 && maxMessageSize <= Integer.MAX_VALUE) {
            // an out of range value is reported by the visitor, so it is not silently truncated here
            target.member(MAX_PAYLOAD_LENGTH, (int) maxMessageSize);
        }
    }
}
