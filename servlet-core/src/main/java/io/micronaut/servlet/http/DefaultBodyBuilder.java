/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.servlet.http;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.web.router.RouteAttributes;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.inject.ExecutionHandle;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.web.router.RouteMatch;
import jakarta.inject.Singleton;

import java.io.EOFException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Default implementation of {@link BodyBuilder}.
 * @author Sergio del Amo
 * @since 4.0.0
 */
@Requires(classes = JsonNode.class)
@Internal
@Singleton
public class DefaultBodyBuilder implements BodyBuilder {
    private static final Set<Class<?>> RAW_BODY_TYPES = CollectionUtils.setOf(String.class, byte[].class, ByteBuffer.class, InputStream.class);

    private final MessageBodyHandlerRegistry messageBodyHandlerRegistry;

    public DefaultBodyBuilder(MessageBodyHandlerRegistry messageBodyHandlerRegistry) {
        this.messageBodyHandlerRegistry = messageBodyHandlerRegistry;
    }

    @Override
    @Nullable
    public Object buildBody(@NonNull Callable<InputStream> bodySupplier,
                             @NonNull HttpRequest<?> request) {
        final MediaType contentType = request.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
        if (BodyBuilder.isFormSubmission(contentType)) {
            return request.getParameters().asMap();
        } else {
            if (request.getContentLength() == 0) {
                return null;
            }
            Argument<?> resolvedBodyType = resolveBodyType(request);
            try (InputStream inputStream = bodySupplier.call())  {
                if (resolvedBodyType != null && RAW_BODY_TYPES.contains(resolvedBodyType.getType())) {
                    return inputStream.readAllBytes();
                } else {
                    Argument<?> targetArgument = resolvedBodyType != null ? resolvedBodyType : Argument.OBJECT_ARGUMENT;
                    Argument<?> effectiveArgument = targetArgument;
                    if (targetArgument == Argument.OBJECT_ARGUMENT && contentType.equals(MediaType.APPLICATION_JSON_TYPE)) {
                        effectiveArgument = Argument.of(JsonNode.class);
                    }
                    @SuppressWarnings("unchecked")
                    MessageBodyReader<Object> reader = (MessageBodyReader<Object>) messageBodyHandlerRegistry
                        .findReader((Argument<Object>) effectiveArgument, contentType)
                        .orElse(null);
                    if (reader != null) {
                        return reader.read((Argument<Object>) effectiveArgument, contentType, request.getHeaders(), inputStream);
                    }
                    return inputStream.readAllBytes();
                }
            } catch (EOFException e) {
                // no content
                return null;
            } catch (Exception e) {
                throw new CodecException("Error decoding request body: " + e.getMessage(), e);
            }
        }
    }

    private Argument<?> resolveBodyType(@NonNull HttpRequest<?> request) {
        RouteMatch<?> route = RouteAttributes.getRouteMatch(request).orElse(null);
        if (route != null) {
            Argument<?> bodyType = route.getRouteInfo().getFullRequestBodyType()
                /*
                The getBodyArgument() method returns arguments for functions where it is
                not possible to dictate whether the argument is supposed to bind the entire
                body or just a part of the body. We check to ensure the argument has the body
                annotation to exclude that use case
                */
                .filter(argument -> {
                    AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
                    if (annotationMetadata.hasAnnotation(Body.class)) {
                        return annotationMetadata.stringValue(Body.class).isEmpty();
                    } else {
                        return false;
                    }
                })
                .orElseGet(() -> {
                    if (route instanceof ExecutionHandle<?, ?> handle) {
                        for (Argument<?> argument : handle.getArguments()) {
                            if (argument.getType() == HttpRequest.class) {
                                return argument;
                            }
                        }
                    }
                    return Argument.OBJECT_ARGUMENT;
                });
            if (bodyType.getType() == HttpRequest.class) {
                bodyType = bodyType.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
            }
            return bodyType;
        } else {
            return Argument.OBJECT_ARGUMENT;
        }
    }
}
