/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.servlet.http.server;

import com.sun.net.httpserver.HttpHandler;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * A {@link HttpHandlerPath} for the path {@value /}.
 */
@Requires(property = "micronaut.server.jdk.enabled", value = "true", defaultValue = "true")
@Requires(beans = HttpHandler.class)
@Requires(missingBeans = HttpHandlerPath.class)
@Singleton
class RootHttpHandlerPath implements HttpHandlerPath {
    private final HttpHandler httpHandler;

    RootHttpHandlerPath(HttpHandler httpHandler) {
        this.httpHandler = httpHandler;
    }

    @Override
    public String getPath() {
        return "/";
    }

    @Override
    public HttpHandler getHttpHandler() {
        return httpHandler;
    }
}
