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
package io.micronaut.servlet.undertow;

import io.micronaut.core.annotation.Internal;
import io.micronaut.servlet.engine.ServletConnectionAborter;
import io.undertow.servlet.spec.HttpServletRequestImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Drops the connection of an Undertow response whose body failed, by closing the connection of its exchange.
 *
 * @author Denis Stepanov
 * @since 6.3.0
 */
@Internal
public final class UndertowConnectionAborter implements ServletConnectionAborter {

    @Override
    public boolean abort(HttpServletRequest request, HttpServletResponse response, Throwable failure) {
        if (!(request instanceof HttpServletRequestImpl undertowRequest)) {
            return false;
        }
        try {
            undertowRequest.getExchange().getConnection().close();
        } catch (IOException e) {
            // the connection is gone either way
        }
        return true;
    }
}
