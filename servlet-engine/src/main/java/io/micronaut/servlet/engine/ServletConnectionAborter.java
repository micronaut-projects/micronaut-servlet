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
package io.micronaut.servlet.engine;

import io.micronaut.core.annotation.Internal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Drops the connection of a response whose body failed after it was committed, so that the client sees a
 * truncated response rather than one that looks complete, like the Netty server does: the Servlet API has no
 * way to do so, completing the response writes the end of its body. Each embedded server registers one for its
 * container with the {@link java.util.ServiceLoader}.
 *
 * @author Denis Stepanov
 * @since 6.3.0
 */
@Internal
public interface ServletConnectionAborter {

    /**
     * @param request  The request of the container
     * @param response The response of the container
     * @param failure  The failure of the body
     * @return Whether the connection was dropped: {@code false} if the request is not of this container
     */
    boolean abort(HttpServletRequest request, HttpServletResponse response, Throwable failure);
}
