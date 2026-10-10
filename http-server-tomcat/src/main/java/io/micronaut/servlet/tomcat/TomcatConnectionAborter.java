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
package io.micronaut.servlet.tomcat;

import io.micronaut.core.annotation.Internal;
import io.micronaut.servlet.engine.ServletConnectionAborter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Response;
import org.apache.catalina.connector.ResponseFacade;
import org.apache.coyote.ActionCode;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * Drops the connection of a Tomcat response whose body failed, by closing it at once through its connector
 * response, which the facade the application sees keeps to itself.
 *
 * @author Denis Stepanov
 * @since 6.3.0
 */
@Internal
public final class TomcatConnectionAborter implements ServletConnectionAborter {

    private static final @Nullable Field RESPONSE = responseField();

    @Override
    public boolean abort(HttpServletRequest request, HttpServletResponse response, Throwable failure) {
        Field field = RESPONSE;
        if (field == null || !(response instanceof ResponseFacade facade)) {
            return false;
        }
        try {
            Response connectorResponse = (Response) field.get(facade);
            connectorResponse.getCoyoteResponse().action(ActionCode.CLOSE_NOW, failure);
            return true;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    private static @Nullable Field responseField() {
        try {
            Field field = ResponseFacade.class.getDeclaredField("response");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
