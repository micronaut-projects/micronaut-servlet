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
 * Maps {@code jakarta.websocket.server.PathParam} to {@code io.micronaut.http.annotation.PathVariable}.
 *
 * <p>Jakarta names the URI template variable in the annotation and leaves the parameter name free,
 * so the name is carried across rather than relying on the parameter name as Micronaut otherwise
 * would.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
public final class JakartaPathParamMapper extends AbstractJakartaWebSocketMapper {

    /**
     * Default constructor.
     */
    public JakartaPathParamMapper() {
        super(PATH_PARAM, PATH_VARIABLE);
    }

    @Override
    void copyMembers(AnnotationValue<Annotation> source, AnnotationValueBuilder<Annotation> target) {
        source.stringValue().ifPresent(target::value);
    }
}
