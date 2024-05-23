/*
 * Copyright 2017-2024 original authors
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

import io.micronaut.context.annotation.Bean;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.TypedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.servlet.annotation.WebListener;
import java.util.List;

/**
 * Allows registering web listeners as beans.
 */
public class WebListenerMapper implements TypedAnnotationMapper<WebListener> {
    @Override
    public Class<WebListener> annotationType() {
        return WebListener.class;
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<WebListener> annotation, VisitorContext visitorContext) {
        return List.of(AnnotationValue.builder(Bean.class).build());
    }
}
